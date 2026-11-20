//! Isolated protocol evaluation. Not connected to Mango clients or production data.

#[cfg(test)]
mod tests {
    use openmls::prelude::*;
    use openmls::prelude::tls_codec::Deserialize;
    use openmls_basic_credential::SignatureKeyPair;
    use openmls_rust_crypto::OpenMlsRustCrypto;
    use openmls_traits::OpenMlsProvider;

    const SUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;

    struct Device {
        provider: OpenMlsRustCrypto,
        signer: SignatureKeyPair,
        identity: CredentialWithKey,
    }

    impl Device {
        fn new(id: &[u8]) -> Self {
            let provider = OpenMlsRustCrypto::default();
            let signer = SignatureKeyPair::new(SUITE.signature_algorithm()).unwrap();
            signer.store(provider.storage()).unwrap();
            let identity = CredentialWithKey {
                credential: BasicCredential::new(id.to_vec()).into(),
                signature_key: signer.to_public_vec().into(),
            };
            Self { provider, signer, identity }
        }

        fn package(&self) -> KeyPackageBundle {
            KeyPackage::builder().build(SUITE, &self.provider, &self.signer, self.identity.clone()).unwrap()
        }

        fn create(&self) -> MlsGroup {
            let config = MlsGroupCreateConfig::builder().ciphersuite(SUITE)
                .use_ratchet_tree_extension(true).build();
            MlsGroup::new(&self.provider, &self.signer, &config, self.identity.clone()).unwrap()
        }

        fn join(&self, message: MlsMessageOut) -> MlsGroup {
            let incoming = MlsMessageIn::tls_deserialize_exact(message.to_bytes().unwrap()).unwrap();
            let MlsMessageBodyIn::Welcome(welcome) = incoming.extract() else {
                panic!("expected Welcome");
            };
            let config = MlsGroupJoinConfig::builder().use_ratchet_tree_extension(true).build();
            StagedWelcome::new_from_welcome(&self.provider, &config, welcome, None)
                .unwrap().into_group(&self.provider).unwrap()
        }

        fn send(&self, group: &mut MlsGroup, text: &[u8]) -> Vec<u8> {
            group.create_message(&self.provider, &self.signer, text).unwrap().to_bytes().unwrap()
        }

        fn receive(&self, group: &mut MlsGroup, wire: &[u8]) -> Result<Vec<u8>, String> {
            let message = MlsMessageIn::tls_deserialize_exact(wire).map_err(|_| "decode")?;
            let protocol = message.try_into_protocol_message().map_err(|_| "type")?;
            let processed = group.process_message(&self.provider, protocol).map_err(|_| "authentication")?;
            match processed.into_content() {
                ProcessedMessageContent::ApplicationMessage(message) => Ok(message.into_bytes()),
                _ => Err("not application data".into()),
            }
        }
    }

    #[test]
    fn private_chat_authentication_replay_and_tampering() {
        let alice = Device::new(b"alice-device-1");
        let bob = Device::new(b"bob-device-1");
        let mut a = alice.create();
        let package = bob.package();
        let (_, welcome, _) = a.add_members(&alice.provider, &alice.signer, &[package.key_package().clone()]).unwrap();
        a.merge_pending_commit(&alice.provider).unwrap();
        let mut b = bob.join(welcome);
        let text = b"Mango private message: not visible to relay";
        let wire = alice.send(&mut a, text);
        assert!(!wire.windows(text.len()).any(|part| part == text));
        assert_eq!(bob.receive(&mut b, &wire).unwrap(), text);
        assert!(bob.receive(&mut b, &wire).is_err(), "replayed ciphertext must fail");
        let mut damaged = alice.send(&mut a, text);
        let last = damaged.len() - 1;
        damaged[last] ^= 1;
        assert!(bob.receive(&mut b, &damaged).is_err());
        let reply = bob.send(&mut b, b"reply");
        assert_eq!(alice.receive(&mut a, &reply).unwrap(), b"reply");
    }

    #[test]
    fn new_device_gets_new_messages_but_not_previous_epoch() {
        let alice = Device::new(b"alice-device-1");
        let bob = Device::new(b"bob-device-1");
        let second = Device::new(b"bob-device-2");
        let mut a = alice.create();
        let (_, welcome, _) = a.add_members(&alice.provider, &alice.signer,
            &[bob.package().key_package().clone()]).unwrap();
        a.merge_pending_commit(&alice.provider).unwrap();
        let mut b = bob.join(welcome);
        let history = alice.send(&mut a, b"before second device was trusted");
        assert_eq!(bob.receive(&mut b, &history).unwrap(), b"before second device was trusted");

        // Trust approval is deliberately outside this protocol-only experiment.
        let (commit, welcome, _) = a.add_members(&alice.provider, &alice.signer,
            &[second.package().key_package().clone()]).unwrap();
        a.merge_pending_commit(&alice.provider).unwrap();
        let incoming = MlsMessageIn::tls_deserialize_exact(commit.to_bytes().unwrap()).unwrap();
        let processed = b.process_message(&bob.provider, incoming.try_into_protocol_message().unwrap()).unwrap();
        if let ProcessedMessageContent::StagedCommitMessage(staged) = processed.into_content() {
            b.merge_staged_commit(&bob.provider, *staged).unwrap();
        } else { panic!("expected membership commit"); }
        let mut b2 = second.join(welcome);
        assert!(second.receive(&mut b2, &history).is_err());
        let message = alice.send(&mut a, b"both Bob devices can read this");
        assert_eq!(bob.receive(&mut b, &message).unwrap(), b"both Bob devices can read this");
        assert_eq!(second.receive(&mut b2, &message).unwrap(), b"both Bob devices can read this");
    }

    #[test]
    fn delayed_delivery_and_group_isolation() {
        let alice = Device::new(b"alice");
        let bob = Device::new(b"bob");
        let mut a = alice.create();
        let (_, welcome, _) = a.add_members(&alice.provider, &alice.signer,
            &[bob.package().key_package().clone()]).unwrap();
        a.merge_pending_commit(&alice.provider).unwrap();
        let mut b = bob.join(welcome);
        let first = alice.send(&mut a, b"offline first");
        let second = alice.send(&mut a, b"offline second");
        assert_eq!(bob.receive(&mut b, &second).unwrap(), b"offline second");
        assert_eq!(bob.receive(&mut b, &first).unwrap(), b"offline first");

        // The same accounts in a different conversation must not accept this ciphertext.
        let mut other_a = alice.create();
        let (_, other_welcome, _) = other_a.add_members(&alice.provider, &alice.signer,
            &[bob.package().key_package().clone()]).unwrap();
        other_a.merge_pending_commit(&alice.provider).unwrap();
        let mut other_b = bob.join(other_welcome);
        let wire = alice.send(&mut a, b"belongs only to the first conversation");
        assert!(bob.receive(&mut other_b, &wire).is_err());
        assert_eq!(bob.receive(&mut b, &wire).unwrap(), b"belongs only to the first conversation");
    }

    #[test]
    fn removed_group_device_cannot_decrypt_new_epoch() {
        let alice = Device::new(b"alice");
        let bob = Device::new(b"bob");
        let carol = Device::new(b"carol");
        let mut a = alice.create();
        let bp = bob.package();
        let cp = carol.package();
        let (_, welcome, _) = a.add_members(&alice.provider, &alice.signer,
            &[bp.key_package().clone(), cp.key_package().clone()]).unwrap();
        a.merge_pending_commit(&alice.provider).unwrap();
        let mut b = bob.join(welcome.clone());
        let mut c = carol.join(welcome);
        let old = alice.send(&mut a, b"all three members");
        assert_eq!(bob.receive(&mut b, &old).unwrap(), b"all three members");
        assert_eq!(carol.receive(&mut c, &old).unwrap(), b"all three members");
        let (commit, _, _) = a.remove_members(&alice.provider, &alice.signer, &[c.own_leaf_index()]).unwrap();
        a.merge_pending_commit(&alice.provider).unwrap();
        let incoming = MlsMessageIn::tls_deserialize_exact(commit.to_bytes().unwrap()).unwrap();
        let processed = b.process_message(&bob.provider, incoming.try_into_protocol_message().unwrap()).unwrap();
        if let ProcessedMessageContent::StagedCommitMessage(staged) = processed.into_content() {
            b.merge_staged_commit(&bob.provider, *staged).unwrap();
        } else { panic!("expected membership commit"); }
        let new = alice.send(&mut a, b"only remaining members");
        assert_eq!(bob.receive(&mut b, &new).unwrap(), b"only remaining members");
        assert!(carol.receive(&mut c, &new).is_err());
    }
}
