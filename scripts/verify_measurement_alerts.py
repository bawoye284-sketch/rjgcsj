"""HTTP acceptance against a disposable H2 COPY, not the user's live database.

Run after starting the demo backend on port 21190 with a copied database.
Creates demo measurements and predictions using only the existing demo account.
No model files, configuration, or original database are modified by this script.
"""
import argparse
import concurrent.futures
import hashlib
import json
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:21190/api/personal-heath/v1.0')
    args = parser.parse_args()
    token = None

    def call(method, path, payload=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['token'] = token
        request = urllib.request.Request(args.base_url + path, method=method, headers=headers,
                                         data=None if payload is None else json.dumps(payload).encode())
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                data = json.load(response)
        except urllib.error.HTTPError as error:
            raise AssertionError(f'{method} {path}: HTTP {error.code}: {error.read().decode()}') from error
        assert data['code'] == 200, (path, data)
        return data.get('data')

    password = hashlib.md5(hashlib.md5(b'123456').hexdigest().encode()).hexdigest()
    token = call('POST', '/user/login', {'userAccount': 'demo_elder', 'userPwd': password})['token']
    personal_input = {'profile': {'age': 72, 'sexMale': 0, 'bmi': 24.5},
                      # Existing demo administrator ranges are 90..119 and 60..79.
                      'measurement': {'systolicPressure': 110, 'diastolicPressure': 70, 'heartRate': 78,
                                      'spo2': 98, 'temperature': 36.5, 'respiratoryRate': 16},
                      'requestId': str(uuid.uuid4())}
    old_profile = call('GET', '/disease-risk/profile')
    profile = {'age': 72, 'sexMale': 0, 'weightKg': 65, 'selfRatedHealth': 3,
               'smokingCurrent': 0, 'drinkingAny': 0, 'exerciseRegular': 1,
               'highCholesterolHistory': 0, 'healthWorsened': 0,
               'weightGain10lb': 0, 'weightLoss10lb': 0, 'hipFractureHistory': 0}
    codes = ['hypertension', 'diabetes', 'stroke', 'heart_attack', 'osteoporosis']
    saved = call('POST', '/disease-risk/profile', {'revision': old_profile['revision'], 'profile': profile,
                  'diseaseHistory': dict.fromkeys(codes, 0), 'diagnosisYears': {}})
    disease_input = {'profileRevision': saved['revision'], 'requestId': str(uuid.uuid4())}
    # Same parallel two-branch flow as HealthAssessment.vue; no model mocking.
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        personal_future = pool.submit(call, 'POST', '/personalized-risk/assess-current', personal_input)
        disease_future = pool.submit(call, 'POST', '/disease-risk/assess', disease_input)
        personal = personal_future.result()
        disease = disease_future.result()
    assert personal['assessmentId'] and personal['measurementId']
    assert personal['assessmentBasis'] == 'NUMERIC5_AGGREGATE_HISTORY_V1'
    assert disease['modelVersion'] == 'nhefs5-local-20261026', disease['modelVersion']
    assert len(disease['results']) == 5
    assert all(r['status'] == 'predicted' for r in disease['results']), disease['results']
    status = call('GET', '/health-alerts/status?measurementId=' + str(personal['measurementId']))
    assert status['status'] == 'COMPLETE', status
    assert status['result']['outcome'] != 'ALERT', status
    replay = call('POST', '/personalized-risk/assess-current', personal_input)
    assert replay['measurementId'] == personal['measurementId']
    before = call('GET', '/personalized-risk/history?days=30')
    call('POST', '/personalized-risk/assess')
    assert call('GET', '/personalized-risk/history?days=30') == before
    latest_status = call('GET', '/health-alerts/status')
    assert latest_status['measurementId'] == personal['measurementId']
    abnormal = call('POST', '/personalized-risk/measurement', {'heartRate': 110})
    check = call('GET', '/health-alerts/status?measurementId=' + str(abnormal['measurementId']))
    assert check['result']['outcome'] == 'ALERT', check
    history = call('GET', '/health-alerts?page=1&size=10')
    assert history['total'] >= 1
    print(json.dumps({'passed': True, 'personalAssessmentId': personal['assessmentId'],
                      'measurementId': personal['measurementId'], 'diseaseModelVersion': disease['modelVersion'],
                      'diseaseResults': {r['disease_code']: r['status'] for r in disease['results']},
                      'normalMeasurementOutcome': status['result']['outcome'],
                      'abnormalMeasurementOutcome': check['result']['outcome'],
                      'replaySameMeasurement': True, 'assessAddsNoMeasurement': True}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
