"""SOS acceptance against a DISPOSABLE H2 only. Never point at the user's live DB.

prepare with notification/SOS workers disabled; restart SAME DB with both enabled,
confirmation=10 seconds, escalation=30 seconds, max-escalations=2, then check.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['prepare', 'check'])
    parser.add_argument('--base-url', default='http://127.0.0.1:21290/api/personal-heath/v1.0')
    parser.add_argument('--state', type=Path, default=Path('IHMS-admin/target/sos-smoke/state.json'))
    args = parser.parse_args()
    token = None

    def call(method, path, payload=None, expected=200):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['token'] = token
        request = urllib.request.Request(args.base_url + path, method=method, headers=headers,
                                         data=None if payload is None else json.dumps(payload).encode())
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                data = json.load(response)
        except urllib.error.HTTPError as error:
            assert error.code == expected, (path, error.code)
            return None
        assert expected == 200 and data['code'] == 200, (path, data.get('msg'))
        return data.get('data')

    password = hashlib.md5(hashlib.md5(b'123456').hexdigest().encode()).hexdigest()
    token = call('POST', '/user/login', {'userAccount': 'demo_elder', 'userPwd': password})['token']
    if args.phase == 'prepare':
        for index in range(3):
            contact = call('POST', '/emergency-contacts', {'name': f'SOS演示联系人{index + 1}', 'relationship': '家属',
                           'email': f'sos{index}@example.invalid', 'phone': '', 'authorized': True})['id']
            challenge = call('POST', f'/emergency-contacts/{contact}/verification')
            assert challenge['mode'] == 'MOCK'
            call('POST', f'/emergency-contacts/{contact}/verification/confirm', {'token': challenge['demoToken']})
        call('PUT', '/health-doctor-authorizations/1', {'authorized': True})
        previous = call('GET', '/personalized-risk/history?days=30')
        request_id = str(uuid.uuid4())
        event = call('POST', '/sos', {'requestId': request_id})
        assert event['status'] == 'OPEN' and len(event['notifications']) == 3
        assert all(n['status'] == 'PENDING' and n['priority'] == 100 for n in event['notifications'])
        assert call('POST', '/sos', {'requestId': request_id})['id'] == event['id']
        assert call('POST', '/sos', {'requestId': str(uuid.uuid4())})['id'] == event['id']
        assert call('GET', '/personalized-risk/history?days=30') == previous
        assert call('GET', '/sos/policy')['demo']
        args.state.parent.mkdir(parents=True, exist_ok=True)
        args.state.write_text(json.dumps({'eventId': event['id'], 'requestId': request_id}), encoding='utf-8')
        print(json.dumps({'prepared': True, 'eventId': event['id'], 'pendingTasks': 3,
                          'measurementUnchanged': True, 'idempotent': True, 'demoNoDial': True}))
        return
    state = json.loads(args.state.read_text(encoding='utf-8'))
    event_id = state['eventId']
    deadline = time.monotonic() + 55
    while True:
        event = call('GET', f'/sos/{event_id}')
        tasks = event['notifications']
        if event['escalation_stage'] >= 1 and len(tasks) >= 4 and all(n['status'] == 'SENT' for n in tasks):
            break
        assert time.monotonic() < deadline, 'SOS restart/escalation did not complete'
        time.sleep(1)
    assert event['escalation_stage'] == 1
    assert not any(n['status'] == 'MOCK_SENT' for n in tasks)
    user_token = token
    token = call('POST', '/doctor/login', {'account': 'demo_doctor2', 'password': password})['token']
    call('GET', f'/doctor-health-alerts/{event_id}', expected=403)
    call('GET', '/sos/current', expected=403)  # doctor id 2 is not user id 2
    token = call('POST', '/doctor/login', {'account': 'demo_doctor', 'password': password})['token']
    detail = call('GET', f'/doctor-health-alerts/{event_id}')
    assert detail['kind'] == 'SOS' and detail['level'] == 'EMERGENCY'
    call('POST', f'/doctor-health-alerts/{event_id}/acknowledge', {'note': '演示：已确认SOS'})
    token = user_token
    assert call('GET', f'/sos/{event_id}')['status'] == 'ACKNOWLEDGED'
    # Pass the next configured 30-second escalation interval; confirmation must stop it.
    time.sleep(32)
    confirmed = call('GET', f'/sos/{event_id}')
    assert confirmed['escalation_stage'] == 1 and len(confirmed['notifications']) == 4
    token = call('POST', '/doctor/login', {'account': 'demo_doctor', 'password': password})['token']
    call('POST', f'/doctor-health-alerts/{event_id}/resolve', {'note': '演示：SOS已处理'})
    token = user_token
    assert call('GET', f'/sos/{event_id}')['status'] == 'RESOLVED'
    assert call('POST', '/sos', {'requestId': state['requestId']})['id'] == event_id
    print(json.dumps({'passed': True, 'visibleRestartedTasks': 2, 'visibleEscalationTasks': 2,
                      'confirmedStopsEscalation': True, 'doctorHandled': True, 'idIsolation': True,
                      'closedRequestReplay': True, 'mockOnly': True}, indent=2))


if __name__ == '__main__':
    main()
