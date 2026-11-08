import unittest
from sso_credentials import MemoryCache,adopt_export

class MemoryCredentialTest(unittest.TestCase):
    def test_refresh_cache_never_writes_to_source(self):
        source={'token':{'value':'old-fixture'}};shared={};cache=MemoryCache(source,shared)
        self.assertEqual(cache['token']['value'],'old-fixture')
        cache['token']={'value':'new-fixture'}
        self.assertEqual(source,{'token':{'value':'old-fixture'}})
        self.assertEqual(MemoryCache(source,shared)['token']['value'],'new-fixture')
    def test_older_cli_export_cannot_regress_new_keys_or_expiration(self):
        c={'AWS_ACCESS_KEY_ID':'new-fixture','AWS_SECRET_ACCESS_KEY':'new-fixture','AWS_SESSION_TOKEN':'new-fixture','AWS_CREDENTIAL_EXPIRATION':'2026-11-08T18:00:00Z'}
        e={'AccessKeyId':'old-fixture','SecretAccessKey':'old-fixture','SessionToken':'old-fixture','Expiration':'2026-11-08T15:00:00Z'}
        self.assertEqual(adopt_export(e,c),c)
        self.assertEqual(e['Expiration'],'2026-11-08T15:00:00Z')
    def test_newer_export_replaces_keys_and_expiry_together(self):
        e={'AccessKeyId':'new-fixture','SecretAccessKey':'new-fixture','SessionToken':'new-fixture','Expiration':'2026-11-08T18:00:00Z'}
        c=adopt_export(e,{})
        self.assertEqual(c['AWS_CREDENTIAL_EXPIRATION'],e['Expiration'])
        self.assertEqual(c['AWS_SESSION_TOKEN'],e['SessionToken'])

if __name__=='__main__':unittest.main()
