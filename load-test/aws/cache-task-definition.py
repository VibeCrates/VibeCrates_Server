"""현재 ECS 정의에서 캐시 스위치 둘만 바꾼 등록 입력을 만든다. AWS 호출은 하지 않는다."""
import argparse
import json
import sys

p = argparse.ArgumentParser()
p.add_argument('mode', choices=['true', 'false'])
p.add_argument('--container', default='backend')
args = p.parse_args()
source = json.load(sys.stdin)
definition = source.get('taskDefinition', source)
readonly = {'taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 'compatibilities',
            'registeredAt', 'registeredBy', 'deregisteredAt'}
result = {k: v for k, v in definition.items() if k not in readonly}
selected = [c for c in result['containerDefinitions'] if c['name'] == args.container]
if len(selected) != 1:
    raise SystemExit('backend 컨테이너를 하나 찾을 수 있어야 합니다')
container = selected[0]
names = {'RECOMMENDATION_CACHE_ENABLED', 'AI_EMBEDDING_CACHE_ENABLED'}
container['environment'] = [e for e in container.get('environment', []) if e['name'] not in names]
container['environment'] += [{'name': name, 'value': args.mode} for name in sorted(names)]
if source.get('tags'):
    result['tags'] = source['tags']
json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
print()
