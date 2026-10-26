"""Local, single-recipient QQ SMTP smoke test. No application/database imports.

Credentials are read only from IHMS_SMTP_TEST_FROM and IHMS_SMTP_TEST_AUTH_CODE.
--check validates configuration without connecting. Otherwise sends exactly once.
"""
import argparse
from dataclasses import dataclass, field
from email.message import EmailMessage
from email.policy import SMTP
from email.utils import formatdate, make_msgid
import json
import os
import re
import smtplib
import socket
import ssl
import sys
import uuid


HOST = 'smtp.qq.com'
PORT = 465
SUBJECT = '健康有道 - 邮件功能测试'
ADDRESS = re.compile(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")


class ConfigurationError(ValueError):
    pass


@dataclass(frozen=True)
class Configuration:
    sender: str = field(repr=False)
    recipient: str = field(repr=False)
    auth_code: str = field(repr=False)
    timeout: int = 15


def configuration(environ, recipient, timeout=15):
    sender = environ.get('IHMS_SMTP_TEST_FROM', '')
    secret = environ.get('IHMS_SMTP_TEST_AUTH_CODE', '')
    if not sender or not secret:
        raise ConfigurationError('请设置 IHMS_SMTP_TEST_FROM 和 IHMS_SMTP_TEST_AUTH_CODE。')
    if not ADDRESS.fullmatch(sender) or sender.rsplit('@', 1)[1].lower() not in ('qq.com', 'foxmail.com'):
        raise ConfigurationError('发件人必须是完整的 QQ 或 Foxmail 邮箱地址。')
    if not recipient or not ADDRESS.fullmatch(recipient):
        raise ConfigurationError('请用 --to 明确指定一个完整的测试邮箱，不支持多个收件人或显示名称。')
    if not secret.isascii() or any(c.isspace() or ord(c) < 33 or ord(c) > 126 for c in secret):
        raise ConfigurationError('SMTP 授权码格式无效，请检查空格或换行。')
    if not 1 <= timeout <= 60:
        raise ConfigurationError('连接超时必须在 1～60 秒之间。')
    return Configuration(sender, recipient, secret, timeout)


def masked(address):
    local, domain = address.rsplit('@', 1)
    return local[:1] + '***@' + domain[:1] + '***'


def message(config):
    test_id = uuid.uuid4().hex
    mail = EmailMessage()
    mail['From'] = config.sender
    mail['To'] = config.recipient
    mail['Subject'] = SUBJECT
    mail['Date'] = formatdate(localtime=True)
    mail['Message-ID'] = make_msgid(domain='qq.com')
    mail.set_content('这是一封健康有道本地开发邮件功能测试邮件。\n'
                     '邮件不包含真实患者资料，不表示健康预警或紧急求助。\n'
                     f'测试编号：{test_id}\n'
                     '收到本邮件即说明本次测试邮件已到达该邮箱。\n')
    return mail, test_id


def send_once(config):
    mail, test_id = message(config)
    result = {'server': HOST, 'port': PORT, 'tls': 'implicit',
              'from': masked(config.sender), 'to': masked(config.recipient),
              'test_id': test_id, 'message_id': str(mail['Message-ID'])}
    smtp = None
    phase = 'CONNECT'

    def failure(code, text, smtp_code=None, uncertain=False):
        result.update(result='UNKNOWN' if uncertain else 'NOT_ACCEPTED', error=code,
                      stage=phase, detail=text, auto_retry=False)
        if isinstance(smtp_code, int) and 100 <= smtp_code <= 599:
            result['smtp_code'] = smtp_code
        return result, 3 if uncertain else 1

    try:
        context = ssl.create_default_context()
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        smtp = smtplib.SMTP_SSL(HOST, PORT, timeout=config.timeout, context=context)
        smtp.set_debuglevel(0)
        phase = 'AUTH'
        smtp.login(config.sender, config.auth_code)
        phase = 'ENVELOPE'
        code, _ = smtp.mail(config.sender)
        if code != 250:
            return failure('SENDER_REJECTED', '服务器未接受发件人，请检查发件账号。', code)
        code, _ = smtp.rcpt(config.recipient)
        if code not in (250, 251):
            return failure('RECIPIENT_REJECTED', '服务器未接受测试收件邮箱，请检查地址或邮箱限制。', code)
        phase = 'DATA'
        code, _ = smtp.data(mail.as_bytes(policy=SMTP))
        if code != 250:
            return failure('MESSAGE_REJECTED', '服务器未接受测试邮件，请检查发信限制或反垃圾策略。', code)
        result.update(result='SMTP_ACCEPTED', smtp_code=code, stage='DATA',
                      detail='SMTP 服务器已接受邮件，不代表已经投递或阅读。请检查测试邮箱。', auto_retry=False)
        return result, 0
    except smtplib.SMTPAuthenticationError as error:
        return failure('AUTH_FAILED', '认证失败：请使用已开启 SMTP 服务的邮箱及其 SMTP 授权码，而非登录密码。', error.smtp_code)
    except ssl.SSLCertVerificationError:
        return failure('TLS_CERTIFICATE_FAILED', '证书校验失败：请检查系统时间、证书或网络代理，不要关闭证书校验。')
    except ssl.SSLError:
        return failure('TLS_FAILED', 'TLS 连接异常；若正在提交，请先检查邮箱再决定是否重试。', uncertain=phase == 'DATA')
    except smtplib.SMTPResponseException as error:
        return failure('SMTP_REJECTED', '服务器未确认接受当前请求，请根据阶段及状态码排查。', error.smtp_code,
                       uncertain=phase == 'DATA' and not 400 <= error.smtp_code <= 599)
    except socket.gaierror:
        return failure('DNS_FAILED', '无法解析 smtp.qq.com，请检查 DNS 和网络。')
    except (TimeoutError, OSError, smtplib.SMTPServerDisconnected):
        return failure('DELIVERY_UNCERTAIN' if phase == 'DATA' else 'CONNECTION_FAILED',
                       '发送结果不确定，请先检查邮箱，勿立即重复发送。' if phase == 'DATA' else '连接失败或超时，邮件尚未提交，请检查网络和 465 端口。',
                       uncertain=phase == 'DATA')
    except KeyboardInterrupt:
        return failure('INTERRUPTED', '测试已中止；如在提交阶段中止，请先检查邮箱再决定是否重试。', uncertain=phase == 'DATA')
    except Exception:
        # Exception text and server replies may contain addresses, AUTH data or other private data.
        return failure('LOCAL_ERROR', '测试遇到本地错误，未输出原始异常或敏感信息。', uncertain=phase == 'DATA')
    finally:
        if smtp is not None:
            try:
                smtp.quit()
            except Exception:
                # A failed QUIT cannot undo a previously observed DATA 250 acceptance.
                pass
            finally:
                try:
                    smtp.close()
                except Exception:
                    pass


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, '参数无效，请使用 --help 查看用法。\n')


def main(argv=None, environ=None):
    parser = SafeParser(description='独立 QQ 邮件测试：固定 smtp.qq.com:465，TLS 校验证书，不连接数据库，不自动重试。')
    parser.add_argument('--to', required=True, help='明确指定的单个测试邮箱；不会读取系统联系人')
    parser.add_argument('--check', action='store_true', help='仅校验配置，不连接 SMTP、不发送邮件')
    parser.add_argument('--timeout', type=int, default=15, help='网络超时秒数，默认15，最大60')
    args = parser.parse_args(argv)
    try:
        config = configuration(os.environ if environ is None else environ, args.to, args.timeout)
    except ConfigurationError as error:
        print(json.dumps({'result': 'CONFIG_INVALID', 'detail': str(error)}, ensure_ascii=False))
        return 2
    if args.check:
        print(json.dumps({'result': 'CONFIG_VALID', 'connected': False, 'sent': False,
                          'server': HOST, 'port': PORT, 'from': masked(config.sender), 'to': masked(config.recipient)}, ensure_ascii=False))
        return 0
    result, status = send_once(config)
    print(json.dumps(result, ensure_ascii=False))
    return status


if __name__ == '__main__':
    sys.exit(main())
