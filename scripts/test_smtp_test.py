"""Offline protocol and privacy checks; never contacts an SMTP server."""
from contextlib import redirect_stdout, redirect_stderr
from email import message_from_bytes, policy
import io
import json
import smtplib
import socket
import ssl
import unittest
from unittest.mock import patch, MagicMock

import smtp_test as tool


ENV = {'IHMS_SMTP_TEST_FROM': 'sender-test@qq.com', 'IHMS_SMTP_TEST_AUTH_CODE': 'FAKE-AUTH-FOR-TEST-ONLY'}
TO = 'receiver-test@qq.com'


class SmtpTest(unittest.TestCase):
    def setUp(self):
        self.config = tool.configuration(ENV, TO)
        self.connection = MagicMock()
        self.connection.mail.return_value = (250, b'ok')
        self.connection.rcpt.return_value = (250, b'ok')
        self.connection.data.return_value = (250, b'accepted')
        self.patcher = patch.object(tool.smtplib, 'SMTP_SSL', return_value=self.connection)
        self.factory = self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def assert_private(self, output):
        text = json.dumps(output, ensure_ascii=False)
        for private in (ENV['IHMS_SMTP_TEST_AUTH_CODE'], ENV['IHMS_SMTP_TEST_FROM'], TO):
            self.assertNotIn(private, text)

    def test_check_is_offline_and_redacted(self):
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(0, tool.main(['--to', TO, '--check'], ENV))
        result = json.loads(output.getvalue())
        self.assertFalse(result['connected'])
        self.factory.assert_not_called()
        self.assert_private(result)

    def test_missing_configuration_never_connects(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(2, tool.main(['--to', TO], {}))
        self.factory.assert_not_called()

    def test_multiple_recipients_header_injection_and_non_qq_sender_rejected(self):
        for recipient in ('a@qq.com,b@qq.com', 'a@qq.com\r\nBcc: b@qq.com', 'Display <a@qq.com>', ''):
            with self.subTest(recipient=recipient), self.assertRaises(tool.ConfigurationError):
                tool.configuration(ENV, recipient)
        with self.assertRaises(tool.ConfigurationError):
            tool.configuration({**ENV, 'IHMS_SMTP_TEST_FROM': 'other@example.com'}, TO)
        self.factory.assert_not_called()

    def test_invalid_secret_and_timeout_are_private(self):
        for secret in ('', 'invalid\ncode', 'contains space', '非ASCII'):
            with self.assertRaises(tool.ConfigurationError):
                tool.configuration({**ENV, 'IHMS_SMTP_TEST_AUTH_CODE': secret}, TO)
        with self.assertRaises(tool.ConfigurationError):
            tool.configuration(ENV, TO, 61)
        self.assertNotIn(ENV['IHMS_SMTP_TEST_AUTH_CODE'], repr(self.config))

    def test_one_tls_connection_exactly_one_plain_text_message(self):
        result, status = tool.send_once(self.config)
        self.assertEqual(0, status)
        self.assertEqual('SMTP_ACCEPTED', result['result'])
        self.assertEqual(250, result['smtp_code'])
        self.factory.assert_called_once()
        args, kwargs = self.factory.call_args
        self.assertEqual(('smtp.qq.com', 465), args)
        self.assertTrue(kwargs['context'].check_hostname)
        self.assertEqual(ssl.CERT_REQUIRED, kwargs['context'].verify_mode)
        self.assertGreaterEqual(kwargs['context'].minimum_version, ssl.TLSVersion.TLSv1_2)
        self.connection.login.assert_called_once_with(self.config.sender, self.config.auth_code)
        self.connection.mail.assert_called_once_with(self.config.sender)
        self.connection.rcpt.assert_called_once_with(TO)
        self.connection.data.assert_called_once()
        mail = message_from_bytes(self.connection.data.call_args.args[0], policy=policy.default)
        self.assertEqual(tool.SUBJECT, mail['Subject'])
        self.assertEqual('text/plain', mail.get_content_type())
        self.assertIn('不包含真实患者资料', mail.get_content())
        self.assertIn(result['test_id'], mail.get_content())
        self.assertIsNone(mail['Cc'])
        self.assertIsNone(mail['Bcc'])
        self.assert_private(result)

    def test_authentication_failure_hides_server_reply(self):
        self.connection.login.side_effect = smtplib.SMTPAuthenticationError(535, ENV['IHMS_SMTP_TEST_AUTH_CODE'].encode())
        result, status = tool.send_once(self.config)
        self.assertEqual(1, status)
        self.assertEqual('AUTH_FAILED', result['error'])
        self.assertEqual(535, result['smtp_code'])
        self.connection.data.assert_not_called()
        self.assert_private(result)

    def test_recipient_rejection_never_submits_body(self):
        self.connection.rcpt.return_value = (550, TO.encode())
        result, status = tool.send_once(self.config)
        self.assertEqual('RECIPIENT_REJECTED', result['error'])
        self.assertEqual(1, status)
        self.connection.data.assert_not_called()
        self.assert_private(result)

    def test_sender_rejection_never_attempts_recipient(self):
        self.connection.mail.return_value = (550, self.config.sender.encode())
        result, status = tool.send_once(self.config)
        self.assertEqual('SENDER_REJECTED', result['error'])
        self.assertEqual(1, status)
        self.connection.rcpt.assert_not_called()
        self.assert_private(result)

    def test_data_rejected_is_not_success(self):
        self.connection.data.return_value = (554, ENV['IHMS_SMTP_TEST_AUTH_CODE'].encode())
        result, status = tool.send_once(self.config)
        self.assertEqual(1, status)
        self.assertEqual('MESSAGE_REJECTED', result['error'])
        self.assertFalse(result['auto_retry'])
        self.assert_private(result)

    def test_timeout_after_submission_is_unknown_and_never_retried(self):
        self.connection.data.side_effect = socket.timeout('private ' + ENV['IHMS_SMTP_TEST_AUTH_CODE'])
        result, status = tool.send_once(self.config)
        self.assertEqual(3, status)
        self.assertEqual('UNKNOWN', result['result'])
        self.assertFalse(result['auto_retry'])
        self.connection.data.assert_called_once()
        self.factory.assert_called_once()
        self.assert_private(result)

    def test_tls_failure_during_data_is_unknown(self):
        self.connection.data.side_effect = ssl.SSLError('private ' + ENV['IHMS_SMTP_TEST_AUTH_CODE'])
        result, status = tool.send_once(self.config)
        self.assertEqual(3, status)
        self.assertEqual('UNKNOWN', result['result'])
        self.assert_private(result)

    def test_auth_timeout_has_not_submitted_mail(self):
        self.connection.login.side_effect = TimeoutError('private')
        result, status = tool.send_once(self.config)
        self.assertEqual(1, status)
        self.assertEqual('NOT_ACCEPTED', result['result'])
        self.connection.data.assert_not_called()

    def test_certificate_validation_failure_has_no_fallback(self):
        self.factory.side_effect = ssl.SSLCertVerificationError('private')
        result, status = tool.send_once(self.config)
        self.assertEqual(1, status)
        self.assertEqual('TLS_CERTIFICATE_FAILED', result['error'])
        self.factory.assert_called_once()

    def test_dns_failure_is_redacted(self):
        self.factory.side_effect = socket.gaierror('private ' + ENV['IHMS_SMTP_TEST_AUTH_CODE'])
        result, status = tool.send_once(self.config)
        self.assertEqual(1, status)
        self.assertEqual('DNS_FAILED', result['error'])
        self.assert_private(result)

    def test_quit_failure_cannot_turn_observed_acceptance_into_failure(self):
        self.connection.quit.side_effect = smtplib.SMTPResponseException(-1, b'private')
        result, status = tool.send_once(self.config)
        self.assertEqual(0, status)
        self.assertEqual('SMTP_ACCEPTED', result['result'])
        self.connection.close.assert_called_once()

    def test_no_recipient_defaults_and_argument_errors_do_not_echo_values(self):
        output = io.StringIO()
        with redirect_stderr(output), self.assertRaises(SystemExit) as exit:
            tool.main(['--password', ENV['IHMS_SMTP_TEST_AUTH_CODE']], ENV)
        self.assertEqual(2, exit.exception.code)
        self.assertNotIn(ENV['IHMS_SMTP_TEST_AUTH_CODE'], output.getvalue())
        self.factory.assert_not_called()


if __name__ == '__main__':
    unittest.main()
