"""Read-only HTTP checks for the six failed captures, against a disposable copy by default.

pending: start the copied demo database with health-notification.worker-enabled=false.
recovered: restart it with the worker enabled, then check automatic recovery and deduplication.
No measurements, contacts or authorization settings are changed by this script.
"""
import argparse
import hashlib
import json
import time
import urllib.request


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('phase', choices=['pending', 'recovered'])
    p.add_argument('--base-url', default='http://127.0.0.1:21190/api/personal-heath/v1.0')
    p.add_argument('--event-id', type=int, help='Only check this event when other browser activity is present')
    a = p.parse_args()
    def selected(data):
        return [t for t in data['items'] if a.event_id is None or t['event_id'] == a.event_id]
    token = None

    def call(path, payload=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['token'] = token
        r = urllib.request.Request(a.base_url + path, headers=headers,
                data=None if payload is None else json.dumps(payload).encode())
        with urllib.request.urlopen(r, timeout=30) as response:
            result = json.load(response)
        assert result['code'] == 200, (path, result.get('msg'))
        return result['data']

    pwd = hashlib.md5(hashlib.md5(b'123456').hexdigest().encode()).hexdigest()
    token = call('/user/login', {'userAccount': 'demo_elder', 'userPwd': pwd})['token']
    data = call('/health-notifications?size=50')
    assert data['mode'] == 'MOCK'
    if a.phase == 'pending':
        assert data['capturePending'] == 6 and len(data['captureErrors']) == 6
        assert data['total'] == 0
        print(json.dumps({'pending': 6, 'failedCheckpoints': 6, 'tasks': 0, 'mode': 'MOCK'}))
        return
    deadline = time.monotonic() + 50
    while data['capturePending'] or any(t['status'] == 'PENDING' for t in data['items']):
        assert time.monotonic() < deadline, 'Capture/delivery did not recover'
        time.sleep(1)
        data = call('/health-notifications?size=50')
    rows = selected(data)
    assert not data['captureErrors'] and len(rows) == 4
    assert not any(t['status'] == 'MOCK_SENT' for t in rows)
    assert sum(t['recipient_type'] == 'DOCTOR' and t['status'] == 'SENT' for t in rows) == 2
    assert all(len(t['attempt_log']) == 1 for t in rows)
    ids = sorted(t['id'] for t in rows)
    time.sleep(17)  # Exercise another scheduled capture/delivery cycle.
    again = call('/health-notifications?size=50')
    assert sorted(t['id'] for t in selected(again)) == ids
    assert all(len(t['attempt_log']) == 1 for t in selected(again))
    token = call('/doctor/login', {'account': 'demo_doctor', 'password': pwd})['token']
    doctor = call('/health-notifications?size=50')
    assert len(selected(doctor)) == 2 and all(t['recipient_type'] == 'DOCTOR' and t['recipient_id'] == 1 for t in selected(doctor))
    print(json.dumps({'passed': True, 'capturePending': 0, 'captureErrors': 0,
                      'visibleTasks': 4, 'mockEmailsExcluded': True, 'doctorNotifications': 2,
                      'deduplication': True, 'oneAttemptPerTask': True, 'mode': 'MOCK'}))


if __name__ == '__main__':
    main()
