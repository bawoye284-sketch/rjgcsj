"""Phase 3 HTTP acceptance; writes only to a DISPOSABLE demo H2 backend on 21190.

prepare: run with health-notification.worker-enabled=false, then stop backend.
check: restart same H2 with worker enabled and run check. No SMTP is used.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['prepare', 'check'])
    parser.add_argument('--base-url', default='http://127.0.0.1:21190/api/personal-heath/v1.0')
    parser.add_argument('--state', type=Path, default=Path('IHMS-admin/target/notification-smoke/state.json'))
    args = parser.parse_args()
    token = None

    def call(method, path, payload=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['token'] = token
        req = urllib.request.Request(args.base_url + path, method=method, headers=headers,
                                     data=None if payload is None else json.dumps(payload).encode())
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                data = json.load(response)
        except urllib.error.HTTPError as error:
            raise AssertionError(f'{method} {path}: HTTP {error.code}') from error
        assert data['code'] == 200, (path, data.get('msg'))
        return data.get('data')

    password = hashlib.md5(hashlib.md5(b'123456').hexdigest().encode()).hexdigest()
    token = call('POST', '/user/login', {'userAccount': 'demo_elder', 'userPwd': password})['token']
    if args.phase == 'prepare':
        contact = call('POST', '/emergency-contacts', {'name': '验收联系人', 'relationship': '家属',
                       'email': 'phase3@example.invalid', 'phone': '13800000000', 'authorized': True})['id']
        challenge = call('POST', f'/emergency-contacts/{contact}/verification')
        assert challenge['mode'] == 'MOCK'
        call('POST', f'/emergency-contacts/{contact}/verification/confirm', {'token': challenge['demoToken']})
        call('PUT', '/health-doctor-authorizations/1', {'authorized': True})
        call('POST', '/personalized-risk/measurement', {'heartRate': 78})
        call('POST', '/personalized-risk/measurement', {'heartRate': 110})
        tasks = call('GET', '/health-notifications?size=50')['items']
        event = max(t['event_id'] for t in tasks)
        tasks = [t for t in tasks if t['event_id'] == event]
        assert len(tasks) == 2 and all(t['status'] == 'PENDING' for t in tasks)  # Initial phase is in-app only.
        args.state.parent.mkdir(parents=True, exist_ok=True)
        args.state.write_text(json.dumps({'event': event, 'taskIds': [t['id'] for t in tasks]}), encoding='utf-8')
        print(json.dumps({'prepared': True, 'pendingTasks': len(tasks), 'mode': 'MOCK'}))
        return

    state = json.loads(args.state.read_text(encoding='utf-8'))
    event = state['event']
    deadline = time.monotonic() + 50
    while True:
        data = call('GET', '/health-notifications?size=50')
        tasks = [t for t in data['items'] if t['id'] in state['taskIds']]
        if len(tasks) == 2 and all(t['status'] == 'SENT' for t in tasks):
            break
        assert time.monotonic() < deadline, 'Persisted tasks did not resume'
        time.sleep(1)
    assert data['mode'] == 'MOCK'
    assert not any(t['status'] == 'MOCK_SENT' for t in data['items'])
    call('POST', '/personalized-risk/measurement', {'heartRate': 111})
    assert len([t for t in call('GET', '/health-notifications?size=50')['items'] if t['event_id'] == event]) == 2
    call('POST', '/personalized-risk/measurement', {'heartRate': 112})
    deadline = time.monotonic() + 40
    while True:
        upgraded = [t for t in call('GET', '/health-notifications?size=50')['items'] if t['event_id'] == event]
        if len(upgraded) == 4 and all(t['status'] == 'SENT' for t in upgraded):
            break
        assert time.monotonic() < deadline, 'Upgrade notifications did not finish'
        time.sleep(1)
    assert sum(t['phase'] == 'REPEATED' for t in upgraded) == 2
    user_token = token
    token = call('POST', '/doctor/login', {'account': 'demo_doctor', 'password': password})['token']
    detail = call('GET', f'/doctor-health-alerts/{event}')
    assert detail['level'] == 'REPEATED'
    call('POST', f'/doctor-health-alerts/{event}/acknowledge', {'note': '验收：已确认'})
    call('POST', f'/doctor-health-alerts/{event}/resolve', {'note': '验收：已记录处理'})
    assert call('GET', f'/doctor-health-alerts/{event}')['handling'] == 'RESOLVED'
    token = user_token
    call('PUT', '/health-doctor-authorizations/1', {'authorized': False})
    token = call('POST', '/doctor/login', {'account': 'demo_doctor', 'password': password})['token']
    assert call('GET', '/doctor-health-alerts')['total'] == 0
    print(json.dumps({'passed': True, 'visibleRestartRecoveredTasks': 2, 'continuousDeduplicated': True,
                      'visibleUpgradeTasks': 2, 'mockEmailsExcluded': True, 'doctorConfirmedAndResolved': True, 'revocationEnforced': True,
                      'mode': 'MOCK'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
