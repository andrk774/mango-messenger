import hashlib
import tempfile
import unittest
from pathlib import Path
from history_archive import prepare, restore, snapshot


class HistoryArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        (self.source / 'history.json').write_bytes(b'{"messages":[{"id":"old-1","body":"private fixture"}],"acl":"account-a"}')
        (self.source / 'voice.bin').write_bytes(bytes(range(256)))
        self.key = bytes(range(32))
        self.archive = self.root / 'archive'
        self.original = snapshot(self.source)
        self.digest = prepare(self.source, self.archive, self.key, 'account-a', 'snapshot-10')

    def test_restore_exact_and_keep_originals(self):
        result = restore(self.archive, self.root/'restore', self.key, 'account-a', 'snapshot-10', self.digest)
        self.assertTrue(result['verified'])
        self.assertFalse(result['sourceDeletionAllowed'])
        self.assertEqual(snapshot(self.root/'restore'), self.original)
        self.assertEqual(snapshot(self.source), self.original)
        self.assertNotIn(b'private fixture', self.archive.read_bytes())

    def test_wrong_key_account_and_checkpoint_fail_before_writing(self):
        for key, account, checkpoint in [(b'x'*32,'account-a','snapshot-10'),
                                         (self.key,'account-b','snapshot-10'),
                                         (self.key,'account-a','snapshot-11')]:
            with self.assertRaises(Exception):
                restore(self.archive,self.root/'denied',key,account,checkpoint,self.digest)
            self.assertFalse((self.root/'denied').exists())

    def test_tampering_even_with_recomputed_digest_fails(self):
        data = bytearray(self.archive.read_bytes()); data[-1] ^= 1
        tampered = self.root/'tampered'; tampered.write_bytes(data)
        with self.assertRaises(Exception):
            restore(tampered,self.root/'denied',self.key,'account-a','snapshot-10',hashlib.sha256(data).hexdigest())
        self.assertFalse((self.root/'denied').exists())

    def test_existing_outputs_are_never_overwritten(self):
        with self.assertRaises(FileExistsError):
            prepare(self.source,self.archive,self.key,'account-a','snapshot-10')
        with self.assertRaises(FileExistsError):
            restore(self.archive,self.source,self.key,'account-a','snapshot-10',self.digest)
        self.assertEqual(snapshot(self.source),self.original)

    def test_archive_cannot_be_written_into_source(self):
        with self.assertRaises(ValueError):
            prepare(self.source,self.source/'archive',self.key,'account-a','snapshot-10')


if __name__ == '__main__':
    unittest.main()
