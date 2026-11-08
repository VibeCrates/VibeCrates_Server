"""기존 CLI SSO 프로필의 역할 자격을 메모리에서 갱신한다. 비밀값을 출력하지 않는다.

AWS CLI 2.38.4의 vendored botocore에 검증했다. CLI 캐시의 15분 갱신 시점보다
먼저 40분 정리 예산을 확보해야 하므로 역할 캐시만 우회한다. 새 자격·SSO 토큰은
디스크에 쓰지 않으며 기존 SSO 로그인·역할·권한을 변경하지 않는다.
"""
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

TOKEN_VALUES = {}
FIELDS = ('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN', 'AWS_CREDENTIAL_EXPIRATION')

class MemoryCache:
    def __init__(self, source, values=None):
        self.source = source
        self.values = {} if values is None else values
    def __contains__(self, key):return key in self.values or key in self.source
    def __getitem__(self, key):return self.values[key] if key in self.values else self.source[key]
    def __setitem__(self, key, value):self.values[key] = value


def adopt_export(exported, current):
    """만료 메타데이터를 키와 함께 교체한다. 오래된 CLI 캐시로 되돌리지 않는다."""
    expiry=lambda s:datetime.datetime.fromisoformat(s.replace('Z','+00:00'))
    if current.get('AWS_CREDENTIAL_EXPIRATION') and expiry(current['AWS_CREDENTIAL_EXPIRATION']) > expiry(exported['Expiration']):
        return {key:current[key] for key in FIELDS}
    return dict(zip(FIELDS,(exported['AccessKeyId'],exported['SecretAccessKey'],exported['SessionToken'],exported['Expiration'])))


def refresh(profile, expected_account):
    try:
        from awscli.botocore.session import Session
        from awscli.botocore.tokens import SSOTokenProvider
    except ModuleNotFoundError:
        entry = Path(shutil.which('aws')).resolve()
        interpreter = Path(entry.read_text().splitlines()[0].removeprefix('#!'))
        candidates = [p for p in (interpreter.parent.parent/'lib').glob('python*/site-packages') if (p/'awscli').is_dir()]
        if len(candidates)!=1:raise RuntimeError('AWS CLI vendored SDK 경로를 하나 찾을 수 없습니다')
        sys.path.insert(0,str(candidates[0]))
        from awscli.botocore.session import Session
        from awscli.botocore.tokens import SSOTokenProvider
    session = Session(profile=profile)
    provider = session.get_component('credential_provider').get_provider('sso')
    cache = MemoryCache(provider._token_cache, TOKEN_VALUES.setdefault(profile, {}))
    provider._token_cache = cache
    provider._token_provider = SSOTokenProvider(session, cache=cache, profile_name=profile)
    provider.cache = {}
    credentials = provider.load()
    frozen = credentials.get_frozen_credentials()
    result = dict(zip(FIELDS,(frozen.access_key,frozen.secret_key,frozen.token,credentials._expiry_time.isoformat())))
    identity = subprocess.run(['aws','sts','get-caller-identity'],env=dict(os.environ,**result),capture_output=True,text=True,check=True)
    if json.loads(identity.stdout)['Account'] != expected_account:raise RuntimeError('SSO account mismatch')
    return result
