import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from api_sweep import judge,prepare

class ApiTest(unittest.TestCase):
    def summary(self, count=600, fail=0, drop=0, p95=1000):
        return {'metrics':{'api_duration{name:recommend}':{'count':600,'med':10,'p(95)':p95,'p(99)':2000},
            'api_failed{name:recommend}':{'value':fail},'aws_api_completed_on_time':{'count':count},
            'dropped_iterations':{'count':drop}}}
    def test_slo_exceeded_continues_until_saturation(self):
        r=judge(self.summary(), 'home', 10,60)
        self.assertFalse(r['goal_pass']);self.assertFalse(r['saturated'])
        for summary in (self.summary(count=539),self.summary(fail=.05),self.summary(drop=1)):
            self.assertTrue(judge(summary,'home',10,60)['saturated'])
        self.assertFalse(judge(self.summary(count=540),'home',10,60)['saturated'])
    def test_replacement_is_saturation_and_preserves_observations(self):
        r=judge(self.summary(),'home',10,60,True)
        self.assertTrue(r['saturated']);self.assertEqual(r['actual_api_per_second'],10)
        self.assertEqual(r['p99'],2000)
    def test_read_prepares_one_api_without_writes_or_token_generation(self):
        data={'screens':[{'id':1}], 'actors':[{'id':2}]}
        with tempfile.TemporaryDirectory() as d,patch('api_sweep.users',return_value=data),patch('api_sweep.screen_prepare') as writes,patch('api_sweep.rows') as db:
            m=prepare(Path(d),'popular',10,60)
            self.assertEqual(set(m['tasks']),{'popular'})
            self.assertEqual(len(m['tasks']['popular']),601)
            self.assertEqual(m['expected'],{'popular':600})
            writes.assert_not_called();db.assert_not_called()
    def test_initial_contents_never_reuses_accounts(self):
        data={'screens':[{'id':1,'token':'fixture'}], 'actors':[]}
        fresh=[{'id':i,'login_id':str(i)} for i in range(10)]
        with tempfile.TemporaryDirectory() as d,patch('api_sweep.users',return_value=data),patch('api_sweep.rows',return_value=fresh),patch('api_sweep.request',return_value=[{'id':1}]),patch('api_sweep.login',side_effect=lambda u:u):
            a=prepare(Path(d),'initial_contents',1,2)
            b=prepare(Path(d),'initial_contents',1,2)
            self.assertTrue({t['user']['id'] for t in a['tasks']['initial_contents']}.isdisjoint({t['user']['id'] for t in b['tasks']['initial_contents']}))
    def test_single_write_uses_screen_reservations_with_full_api_rate(self):
        with tempfile.TemporaryDirectory() as d,patch('api_sweep.screen_prepare',return_value={}) as fn:
            prepare(Path(d),'save_board',80,60)
            fn.assert_called_once_with(Path(d),80,60,only='save_board')

if __name__=='__main__':unittest.main()
