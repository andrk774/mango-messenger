import copy
import json
import os
import tempfile
from pathlib import Path
import unittest
import uuid

from migrate_live_history import (build_payload, seal, verify, verify_run, exclusive_write, digest, safe_storage_key,
                                  bounded_command, read_snapshot, ROOT)


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.data = b'voice fixture\x00\xff'
        self.account = {'account':str(uuid.uuid4()),'conversations':[{'id':'chat'}],
                        'messages':[{'id':'message','body':'private fixture'}],
                        'attachments':[{'id':'file','size_bytes':len(self.data),
                                        'checksum_sha256':digest(self.data)}]}
        self.clear = build_payload(self.account,'snapshot-1',lambda _: self.data)
        self.key = bytes(range(32))
        self.sealed = seal(self.clear,self.key,self.account['account'],'snapshot-1')
        self.receipt = {'account':self.account['account'],'checkpoint':'snapshot-1',
                        'archiveSha256':digest(self.sealed),'sourceSha256':digest(self.clear)}

    def test_exact_restore_and_random_nonces(self):
        self.assertEqual(verify(self.sealed,self.key,self.receipt),
                         {'messages':1,'attachments':1,'conversations':1})
        self.assertNotIn(b'private fixture',self.sealed)
        self.assertNotEqual(self.sealed,seal(self.clear,self.key,self.account['account'],'snapshot-1'))

    def test_wrong_key_account_checkpoint_and_source_digest(self):
        with self.assertRaises(Exception):
            verify(self.sealed,b'x'*32,self.receipt)
        for name, value in [('account',str(uuid.uuid4())),('checkpoint','old'),('sourceSha256','0'*64)]:
            altered = dict(self.receipt, **{name:value})
            with self.assertRaises(Exception):
                verify(self.sealed,self.key,altered)

    def test_modified_ciphertext_even_if_outer_hash_is_replaced(self):
        altered = self.sealed[:-1] + bytes([self.sealed[-1] ^ 1])
        with self.assertRaises(Exception):
            verify(altered,self.key,dict(self.receipt,archiveSha256=digest(altered)))

    def test_source_blob_changed_or_missing(self):
        with self.assertRaises(ValueError):
            build_payload(self.account,'snapshot-1',lambda _: b'changed')
        def missing(_):
            raise FileNotFoundError()
        with self.assertRaises(FileNotFoundError):
            build_payload(self.account,'snapshot-1',missing)

    def test_authenticated_manifest_cannot_omit_a_blob(self):
        malformed = json.loads(self.clear)
        malformed['blobs'] = []
        clear = json.dumps(malformed).encode()
        sealed = seal(clear,self.key,self.account['account'],'snapshot-1')
        with self.assertRaises(ValueError):
            verify(sealed,self.key,dict(self.receipt,sourceSha256=digest(clear),archiveSha256=digest(sealed)))

    def test_paths_and_duplicate_attachment_ids(self):
        for path in ['/etc/passwd','../secret','a/../b','a\\b','a:b','a//b','a\x00b']:
            with self.assertRaises(ValueError):
                safe_storage_key(path)
        self.assertEqual(safe_storage_key('abc/file'), 'abc/file')
        account = copy.deepcopy(self.account)
        account['attachments'] *= 2
        with self.assertRaises(ValueError):
            build_payload(account,'snapshot-1',lambda _: self.data)

    def test_disk_restore_detects_incomplete_archive_set_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archives, trusted = root/'archives', root/'trusted'
            archives.mkdir()
            trusted.mkdir()
            account = self.account['account']
            archive = archives/(account+'.mango-history')
            exclusive_write(archive,self.sealed)
            exclusive_write(trusted/(account+'.key'),self.key)
            exclusive_write(trusted/(account+'.receipt.json'),json.dumps(self.receipt).encode())
            self.assertTrue(verify_run(archives,trusted)['verified'])
            with self.assertRaises(FileExistsError):
                exclusive_write(archive,b'overwrite')
            self.assertEqual(archive.read_bytes(),self.sealed)
            empty = root/'empty'
            empty.mkdir()
            with self.assertRaises(ValueError):
                verify_run(empty,trusted)


@unittest.skipUnless(os.environ.get('MANGO_HISTORY_DB_TEST') == '1','Explicit isolated PostgreSQL test required')
class SnapshotAclTests(unittest.TestCase):
    def test_real_postgres_snapshot_isolates_accounts_and_redacts_tombstones(self):
        # New isolated database only. Retained for diagnostics, never drop live data.
        name = 'mango_history_test_' + uuid.uuid4().hex
        pg = 'mango-postgres-1'
        bounded_command(['docker','exec',pg,'createdb','-U','mango',name],1024)
        def sql(query):
            return bounded_command(['docker','exec','-i',pg,'psql','-X','-qAt','-v',
                'ON_ERROR_STOP=1','-U','mango','-d',name],1024*1024,query.encode())
        schema = ROOT/'backend/app/src/main/resources/db/migration/V001__init.sql'
        sql(schema.read_text(encoding='utf-8'))
        a,b,c,chat,private,m1,m2,m3 = [str(uuid.uuid4()) for _ in range(8)]
        sql(f"""
        INSERT INTO users(id,email,password_hash,username,display_name) VALUES
        ('{a}','a@example.test','fixture','alice','Alice'),
        ('{b}','b@example.test','fixture','bob','Bob'),
        ('{c}','c@example.test','fixture','carol','Carol');
        INSERT INTO conversations(id,type,title) VALUES ('{chat}','GROUP','Shared'),('{private}','GROUP','Private');
        INSERT INTO conversation_members(conversation_id,user_id,draft) VALUES
        ('{chat}','{a}','secret draft'),('{chat}','{b}',NULL),('{chat}','{c}',NULL),('{private}','{a}',NULL);
        UPDATE conversation_members SET left_at=now() WHERE user_id='{c}';
        INSERT INTO messages(id,conversation_id,sender_id,client_msg_id,seq,type,body) VALUES
        ('{m1}','{chat}','{a}','one',1,'TEXT','shared'),
        ('{m2}','{private}','{a}','two',1,'TEXT','only Alice'),
        ('{m3}','{chat}','{a}','three',2,'TEXT','deleted secret');
        UPDATE messages SET deleted_at=now() WHERE id='{m3}';
        """)
        snapshot = read_snapshot(pg,name)
        accounts = {x['account']:x for x in snapshot['accounts']}
        self.assertEqual(len(accounts[a]['messages']),3)
        self.assertEqual(len(accounts[b]['messages']),2)
        self.assertEqual(accounts[c]['messages'],[])
        self.assertNotIn('secret draft',json.dumps(snapshot))
        self.assertNotIn('deleted secret',json.dumps(snapshot))
        self.assertNotIn('only Alice',json.dumps(accounts[b]))
        # Query was read-only; source body retained even for tombstone fixture.
        self.assertEqual(sql(f"SELECT body FROM messages WHERE id='{m3}'").decode().strip(),'deleted secret')
        print('Isolated fixture database retained:',name)


if __name__ == '__main__':
    unittest.main()
