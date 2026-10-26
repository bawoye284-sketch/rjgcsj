"""Acceptance on a DISPOSABLE demo H2 backend only (port 21190 by default).

prepare: worker disabled, reminder interval set to 10 seconds; saves pending tasks.
check: restart the same database with delivery enabled; checks persistence and doctor response.
"""
import argparse
import hashlib
import json
import time
import uuid
from pathlib import Path
import urllib.request
import urllib.error


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('phase', choices=['prepare', 'check'])
    p.add_argument('--base-url', default='http://127.0.0.1:21190/api/personal-heath/v1.0')
    p.add_argument('--state', type=Path, default=Path('IHMS-admin/target/product-final/reminder-state.json'))
    args = p.parse_args()
    token = None

    def call(method, path, data=None, expected=200):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['token'] = token
        req = urllib.request.Request(args.base_url + path, method=method, headers=headers,
                    data=None if data is None else json.dumps(data).encode())
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            assert error.code == expected, (path, error.code)
            return None
        assert expected == 200 and result['code'] == 200, (path, result.get('msg'))
        return result['data']

    password = hashlib.md5(hashlib.md5(b'123456').hexdigest().encode()).hexdigest()
    token = call('POST', '/user/login', {'userAccount': 'demo_elder', 'userPwd': password})['token']
    user_token = token
    if args.phase == 'prepare':
        c = call('POST', '/emergency-contacts', {'name': '演示联系人', 'relationship': '家属', 'email': 'reminder@example.invalid', 'phone': '', 'authorized': True})
        challenge = call('POST', f'/emergency-contacts/{c["id"]}/verification')
        call('POST', f'/emergency-contacts/{c["id"]}/verification/confirm', {'token': challenge['demoToken']})
        call('PUT', '/health-doctor-authorizations/1', {'authorized': True})
        event = call('POST', '/sos', {'requestId': str(uuid.uuid4())})
        event_id = event['id']
        assert not event['reminderAllowed']
        key = str(uuid.uuid4())
        call('POST', f'/sos/{event_id}/reminders', {'requestId': key}, expected=429)
        deadline = time.monotonic() + 20
        while not call('GET', '/sos/current')['reminderAllowed']:
            assert time.monotonic() < deadline
            time.sleep(1)
        reminder = call('POST', f'/sos/{event_id}/reminders', {'requestId': key})
        replay = call('POST', f'/sos/{event_id}/reminders', {'requestId': key})
        assert reminder['id'] == replay['id'] == event_id
        assert len(reminder['notifications']) == len(replay['notifications']) == 6
        assert all(n['status'] == 'PENDING' for n in reminder['notifications'])
        for _ in range(3):
            assert len(call('GET', '/sos/current')['notifications']) == 6
        args.state.parent.mkdir(parents=True, exist_ok=True)
        args.state.write_text(json.dumps({'event': event_id, 'key': key, 'ids': [n['id'] for n in reminder['notifications'] if n['channel'] == 'IN_APP']}), encoding='utf-8')
        print(json.dumps({'prepared': True, 'pendingTasks': 6, 'oneActiveEvent': True, 'replayAndRefreshSafe': True}))
        return
    state = json.loads(args.state.read_text(encoding='utf-8'))
    deadline = time.monotonic() + 50
    while True:
        event = call('GET', f'/sos/{state["event"]}')
        original = [n for n in event['notifications'] if n['id'] in state['ids']]
        if len(original) == 4 and all(n['status'] == 'SENT' for n in original):
            break
        assert time.monotonic() < deadline, 'Persisted reminders did not resume'
        time.sleep(1)
    assert sorted(n['id'] for n in original) == sorted(state['ids'])
    assert not any(n['status'] == 'MOCK_SENT' for n in event['notifications'])
    replay = call('POST', f'/sos/{state["event"]}/reminders', {'requestId': state['key']})
    assert len([n for n in replay['notifications'] if n['phase'].startswith('SOS_R_')]) == 2
    token = call('POST', '/doctor/login', {'account': 'demo_doctor', 'password': password})['token']
    notice = call('GET', '/health-notifications?size=50')
    assert sum(n['id'] in state['ids'] for n in notice['items']) == 2
    call('POST', f'/doctor-health-alerts/{state["event"]}/acknowledge', {'note': '已联系本人'})
    token = user_token
    assert call('GET', f'/sos/{state["event"]}')['status'] == 'ACKNOWLEDGED'
    token = call('POST', '/doctor/login', {'account': 'demo_doctor', 'password': password})['token']
    call('POST', f'/doctor-health-alerts/{state["event"]}/resolve', {'note': '演示处理完成'})
    token = user_token
    assert call('GET', f'/sos/{state["event"]}')['status'] == 'RESOLVED'
    new = call('POST', '/sos', {'requestId': str(uuid.uuid4())})
    assert new['id'] != state['event']
    print(json.dumps({'passed': True, 'visibleInAppTasksRecovered': 4, 'mockEmailsExcluded': True, 'doctorMessages': 2,
                      'idempotentAfterRestart': True, 'doctorConfirmedAndResolved': True, 'newEventAfterResolution': True}))


if __name__ == '__main__':
    main()
