"""서버 없이 판정의 경계·카운터 초기화·배포 입력 보존을 검증한다."""
import json
from pathlib import Path
import subprocess
import sys
import unittest
import tempfile
from unittest.mock import patch

from collect import cache_delta, latest_sample
from model import counts
from sweep import judge, measurement_start
from keywords import KEYWORD_COUNT, Keywords, embedding_key, keyword
from planner import Resources, demands, sweep_plan, unlike_candidates
from verify import unlikes


class ToolsTest(unittest.TestCase):
    def summary(self, failed=0, on_time=100, dropped=0, p95=800):
        return {'metrics': {
            'api_failed': {'value': failed, 'passes': 100, 'fails': 0},
            'aws_completed': {'count': 100}, 'aws_completed_on_time': {'count': on_time},
            'aws_completed{name:home}': {'count': 100}, 'dropped_iterations': {'count': dropped},
            'api_duration{name:recommend}': {'count': 100, 'p(95)': p95},
            'screen_duration{screen:home}': {'count': 100, 'p(95)': p95},
        }}

    def result(self, **kwargs):
        return judge(self.summary(**kwargs), {'tasks': {'home': [None] * 100}}, {}, 100)

    def test_slo_exceeded_does_not_stop(self):
        result = self.result(p95=900)
        self.assertFalse(result['goal_pass'])
        self.assertFalse(result['saturated'])

    def test_saturation_boundaries(self):
        self.assertFalse(self.result(failed=.049, on_time=90)['saturated'])
        self.assertTrue(self.result(failed=.05)['saturated'])
        self.assertTrue(self.result(dropped=1)['saturated'])
        # drain 포함 완료 수가 100이어도 구간 내 89건이면 포화다.
        self.assertTrue(self.result(on_time=89)['saturated'])

    def test_multi_task_cache_delta(self):
        key = lambda r: f'crates_recommendation_cache_total{{result="{r}"}}'
        before = {'a': {key('hit'): 10, key('miss'): 5}, 'b': {key('hit'): 3, key('miss'): 2}}
        after = {'a': {key('hit'): 12, key('miss'): 7}, 'b': {key('hit'): 6, key('miss'): 5}}
        self.assertEqual(cache_delta(before, after)['recommendation']['hit_rate'], .5)
        self.assertIsNone(cache_delta(before, before)['embedding']['hit_rate'])
        with self.assertRaises(RuntimeError):
            cache_delta(after, before)
        with self.assertRaises(RuntimeError):
            cache_delta(before, {'a': after['a']})

    def test_throughput_excludes_drain(self):
        summary = self.summary(on_time=89)
        summary['metrics']['aws_completed_on_time{kind:screen}'] = {'count': 40}
        summary['metrics']['aws_api_completed_on_time'] = {'count': 80}
        result = judge(summary, {'tasks': {'home': [None] * 100}}, {}, 100)
        self.assertEqual(result['actual_iterations_per_second'], .89)
        self.assertEqual(result['actual_screens_per_second'], .4)
        self.assertEqual(result['actual_api_per_second'], .8)
        self.assertFalse(result['throughput_includes_drain'])

    def test_legacy_summary_throughput(self):
        result = self.result()
        self.assertEqual(result['actual_screens_per_second'], 1)
        self.assertEqual(result['actual_api_per_second'], 1)
        self.assertTrue(result['throughput_includes_drain'])

    def test_complete_metrics_sample(self):
        text = ('AWS_SAMPLE 10\r\nhikaricp_connections_pending{pool="test"} 1\r\nAWS_SAMPLE_END\r\n'
                'AWS_SAMPLE 12\r\nhikaricp_connections_pending{pool="test"} 2\r\nAWS_SAMPLE_END\r\n'
                'AWS_SAMPLE 14\r\nhikaricp_connections_pending{pool="test"} 3\r\n')
        self.assertEqual(latest_sample(text), (12, {'hikaricp_connections_pending{pool="test"}': 2}))
        self.assertIsNone(latest_sample('AWS_SAMPLE 10\n'))

    def test_measurement_start_excludes_vu_initialization(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'k6.log'
            path.write_text('initializing VUs\n[]\n' + json.dumps({'msg': 'AWS_STAGE_START 1794070000123'}) + '\n')
            self.assertEqual(measurement_start(path), 1794070000.123)
            path.write_text('initialization failed\n')
            self.assertIsNone(measurement_start(path))

    def test_low_rate_keeps_rare_action(self):
        values = counts(2, 45)
        self.assertEqual(values['delete_board'], 1)
        self.assertEqual(values['home'], 62)
        self.assertNotIn('search_results', counts(2, 45, True))
        self.assertEqual(counts(2, 45, True)['home'], values['home'])
        # 양수 .5 반올림은 JS Math.round와 맞추고 Python의 짝수 반올림을 쓰지 않는다.
        self.assertEqual(counts(1.25, 10000)['delete_board'], 63)

    def test_cache_definition_preserves_other_settings(self):
        source = {'taskDefinition': {'taskDefinitionArn': 'old', 'revision': 3, 'family': 'test', 'cpu': '2048',
            'runtimePlatform': {'cpuArchitecture': 'ARM64'}, 'containerDefinitions': [{'name': 'backend',
            'image': 'image@sha256:123', 'secrets': [{'name': 'SECRET', 'valueFrom': 'ref'}],
            'environment': [{'name': 'OTHER', 'value': 'kept'}, {'name': 'RECOMMENDATION_CACHE_ENABLED', 'value': 'true'}]}]}}
        script = Path(__file__).with_name('cache-task-definition.py')
        p = subprocess.run([sys.executable, str(script), 'false'], input=json.dumps(source), text=True, capture_output=True, check=True)
        result = json.loads(p.stdout)
        self.assertNotIn('revision', result)
        self.assertEqual(result['cpu'], '2048')
        self.assertEqual(result['runtimePlatform'], source['taskDefinition']['runtimePlatform'])
        container = result['containerDefinitions'][0]
        self.assertEqual(container['image'], 'image@sha256:123')
        self.assertEqual(container['secrets'], [{'name': 'SECRET', 'valueFrom': 'ref'}])
        env = {v['name']: v['value'] for v in container['environment']}
        self.assertEqual(env, {'OTHER': 'kept', 'RECOMMENDATION_CACHE_ENABLED': 'false', 'AI_EMBEDDING_CACHE_ENABLED': 'false'})

    def test_natural_keywords_are_exhaustive_and_cursor_is_durable(self):
        self.assertEqual(len({keyword(i) for i in range(KEYWORD_COUNT)}), KEYWORD_COUNT)
        with self.assertRaises(RuntimeError):
            keyword(KEYWORD_COUNT)
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            self.assertEqual([t['keywordIndex'] for t in Keywords(directory).take(3)], [0,1,2])
            self.assertEqual(Keywords(directory).take(1)[0]['keywordIndex'], 3)
            cursor = Keywords(directory)
            cursor.next = KEYWORD_COUNT-1
            with self.assertRaises(RuntimeError):
                cursor.take(2)

    def test_embedding_key_matches_nfc_java_strip_and_ascii_whitespace(self):
        self.assertEqual(embedding_key('\u2003 비\t\n 오는  날 \u2003'), 'emb:v1:비 오는 날')
        self.assertEqual(embedding_key('\u00a0비\u00a0오는\u00a0'), 'emb:v1:\u00a0비\u00a0오는\u00a0')

    def test_full_sweep_budget_includes_warmups_refinement_and_spares(self):
        stages = sweep_plan([10,20,40,80,160,320,640],120,30,3)
        total, per_actor = demands(stages)
        self.assertEqual(len(stages),32)
        self.assertEqual(total['unlike'],65112)
        self.assertEqual(total['onboarding'],10577)
        self.assertEqual(total['save_board'],105482)
        self.assertEqual(sum(per_actor['save_recommend'].values())+sum(per_actor['save_stored'].values()),105482)

    def test_save_excludes_previous_reservations_and_existing_owned_identity(self):
        r = Resources.__new__(Resources)
        r.own_pairs, r.reserved = set(), set()
        u, b = {'id':7}, {'title':'같은 제목','contents':[{'id':2},{'id':1}]}
        self.assertTrue(r.can_save(u,b))
        r.reserved.add('save|7|같은 제목|1,2')
        self.assertFalse(r.can_save(u,b))
        self.assertTrue(r.can_save({'id':8},b))
        r.reserved.clear()
        r.own_pairs.add('7|같은 제목|1,2')
        self.assertFalse(r.can_save(u,b))

    def test_payload_preserves_layout_and_content_titles(self):
        source = (Path(__file__).parents[1] / 'scenarios/aws-screens.js').read_text()
        payload = source[source.index('function payload(t)'):source.index('function verifyMutation')]
        program = """let exec={scenario:{iterationInTest:0}},runId='test',contents=[{id:999}];
        const t={board:{title:'원본',contents:Array.from({length:8},(_,i)=>({id:i+1})),layout:null}};
        """+payload+"""
        for(let i=0;i<10;i++) {exec.scenario.iterationInTest=i;const [b,m]=payload(t);
          if(m!=='title' && b.title!=='원본') throw Error('제목 변경');
          if(m==='title' && b.title==='원본') throw Error('제목 모드 미변경');
          if(m==='content' && !b.contentIds.includes(999)) throw Error('콘텐츠 미변경');}
        """
        subprocess.run(['node','--input-type=module','-e',program],check=True,capture_output=True)

    def test_preflight_stops_before_tokens_when_actor_pairs_or_unlikes_run_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = Resources.__new__(Resources)
            r.reserved, r.owned, r.public = set(), [], [None]*256
            r.keywords = type('Cursor', (), {'next':0})()
            r.remaining = lambda demand,name: {0:(1,0),1:(1,100)} if name=='stored_like' else {}
            stage = {'onboarding':1,'unlike':1,'edit_board':0,'delete_board':0,'save_board':0,'stored_like':2}
            for unlikes in ([], [None]):
                with self.subTest(unlike_available=len(unlikes)):
                    r.unlikes = unlikes
                    path = Path(tmp)/'budget.json'
                    with patch('planner.rows', return_value=[{'id':7}]), patch('planner.request') as api:
                        with self.assertRaises(RuntimeError):
                            r.preflight([stage],path)
                    api.assert_not_called()
                    report = json.loads(path.read_text())
                    self.assertIn('stored_like',report['shortages'])
                    self.assertEqual('unlike' in report['shortages'],not unlikes)
                    self.assertGreater(report['resources']['stored_like']['available'],2)

    def test_unlike_protects_dependencies_and_mixes_actual_types_and_actors(self):
        actors = [{'id':i} for i in range(600)]
        liked = [{'user_id':i, 'board_id':i*10+j, 'owner_id':None,
                  'board_type':'TASTE_RECOMMEND' if j<3 else 'SEARCH_RECOMMEND'}
                 for i in range(600) for j in range(4)]
        extra = [dict(liked[0], board_id=99991, owner_id=0),
                 dict(liked[0], board_id=99992), dict(liked[0], board_id=99993),
                 dict(liked[0], board_id=99994, user_id=9999)]
        result = unlike_candidates(liked+extra, actors, {99992}, {'unlike|0|99993'})
        self.assertEqual(len(result),2400)
        self.assertEqual(result,unlike_candidates(liked+extra,actors,{99992},{'unlike|0|99993'}))
        for end in (4,100,600,1200,2400):
            self.assertLessEqual(abs(sum(r['board_type']=='SEARCH_RECOMMEND' for r in result[:end])-end/4),1)
        self.assertGreater(len({r['user_id'] for r in result[:600]}),400)

    def test_shared_active_custom_is_safe_but_mutable_and_deletable_capture_are_protected(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory/'capture-boards.json').write_text(json.dumps([{'boardId':1},{'boardId':2000}]))
            public = [{'id':i,'title':str(i),'content_signature':'1,2'} for i in range(1,257)]
            owned = [{'id':1000,'user_id':2,'title':'owned','content_signature':'1,2'}]
            liked = [{'user_id':1,'board_id':bid,'owner_id':owner,'board_type':kind,
                      'title':str(bid),'content_signature':'1,2','like_count':1,'deleted_at':None}
                     for bid,owner,kind in ((1,3,'USER_CUSTOM'),(1000,2,'USER_CUSTOM'),
                         (2000,None,'SEARCH_RECOMMEND'),(3000,None,'TASTE_RECOMMEND'),(4000,1,'USER_CUSTOM'))]
            with patch('planner.rows',side_effect=[public,owned,liked]):
                r = Resources(directory,{'actors':[{'id':1}]})
            self.assertEqual({p['board_id'] for p in r.unlikes},{1,3000})
            self.assertEqual(r.unlike_pool['protected_boards'],2)

    def test_token_preparation_shares_each_search_board_and_stops_at_full_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = Resources.__new__(Resources)
            r.directory = Path(tmp)
            r.catalog = {'recommend':[], 'search':[]}
            r.actors = [{'id':i, 'token':'test'} for i in range(600)]
            r.generated, r.reserved, r.own_pairs, r.stable_capacity = set(),set(),set(),{}
            r.keywords, r.persist = Keywords(r.directory), lambda:None
            per_actor = {name:{} for name in ('recommend_like','save_recommend','search_like')}
            per_actor['search_like'] = {i:3 for i in range(600)}
            candidates = [{'title':str(i),'contents':[{'id':i}], 'likeToken':'test'} for i in range(3)]
            with patch('planner.request', side_effect=candidates) as api, patch('planner.time.sleep') as sleep:
                report = r.prepare_tokens(per_actor,10,r.directory/'token-preparation.json')
            self.assertEqual(api.call_count,3)
            self.assertEqual(sleep.call_count,2)
            self.assertEqual(report['requests'],{'recommend':0,'search':3})
            self.assertTrue(all(left==need for need,left in r.remaining(per_actor,'search_like').values()))
            with patch('planner.request') as api:
                r.prepare_tokens(per_actor,10,r.directory/'again.json')
                api.assert_not_called()

    def test_home_tokens_are_also_prepared_for_all_actors_before_stages(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = Resources.__new__(Resources)
            r.directory = Path(tmp)
            r.catalog = {'recommend':[], 'search':[]}
            r.actors = [{'id':i,'token':'test'} for i in range(600)]
            r.generated, r.reserved, r.own_pairs, r.stable_capacity = set(),set(),set(),{}
            r.keywords, r.persist = Keywords(r.directory), lambda:None
            per_actor = {'recommend_like':{i:2 for i in range(600)},
                         'save_recommend':{i:1 for i in range(600)},'search_like':{}}
            boards = [{'title':str(i),'contents':[{'id':i}],'likeToken':'test','boardId':None} for i in range(4)]
            with patch('planner.request',return_value=boards) as api:
                report = r.prepare_tokens(per_actor,0,r.directory/'tokens.json')
            self.assertEqual(api.call_count,1)
            self.assertEqual(report['requests'],{'recommend':1,'search':0})
            self.assertTrue(all(need<=left for name in ('recommend_like','save_recommend')
                                for need,left in r.remaining(per_actor,name).values()))

    def test_home_preparation_continues_past_users_with_duplicate_recommendations(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = Resources.__new__(Resources)
            r.directory = Path(tmp)
            old = {'title':'old','contents':[{'id':1}],'boardId':None}
            new = {'title':'new','contents':[{'id':2}],'boardId':None}
            r.catalog = {'recommend':[old], 'search':[]}
            r.actors = [{'id':i,'token':'test'} for i in range(600)]
            r.generated, r.reserved, r.own_pairs, r.stable_capacity = set(),set(),set(),{}
            r.keywords, r.persist = Keywords(r.directory), lambda:None
            per_actor = {'recommend_like':{i:2 for i in range(600)},'save_recommend':{},'search_like':{}}
            with patch('planner.request',side_effect=[[old],[new]]) as api, patch('planner.time.sleep'):
                report = r.prepare_tokens(per_actor,0,r.directory/'tokens.json')
            self.assertEqual(api.call_count,2)
            self.assertEqual(report['categories']['recommend']['added_boards'],1)

    def test_unlike_verification_rejects_response_without_actual_deletion(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            log = path/'k6.log'
            log.write_text(json.dumps({'msg':'UNLIKE_VERIFICATION '+json.dumps({'userId':1,'boardId':9,
                'boardType':'SEARCH_RECOMMEND','ok':True,'returnedBoardId':None,'likeCount':0,'deleted':True})}))
            manifest = {'tasks':{'unlike':[{'user':{'id':1},'boardId':9,'boardType':'SEARCH_RECOMMEND',
                                           'likeCountBefore':1,'softDeleted':False}]}}
            for board_rows, passed in (([],True),([{'id':9,'like_count':0}],False)):
                with patch('verify.rows',side_effect=[board_rows,[],[{'user_id':1,'board_id':'9'}],[]]):
                    if passed:
                        self.assertTrue(unlikes(log,manifest,0,path/'report.json')['passed'])
                    else:
                        with self.assertRaises(RuntimeError):
                            unlikes(log,manifest,0,path/'report.json')


if __name__ == '__main__':
    unittest.main()
