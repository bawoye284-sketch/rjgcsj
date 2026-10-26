import re
import unittest
from prepare_smtp_mysql import generate


class SchemaTest(unittest.TestCase):
    def test_requires_new_isolated_name(self):
        for name in ['personal_health', 'ihms_smtp_test_', 'ihms_smtp_test_x;DROP TABLE user', '']:
            with self.assertRaises(ValueError):
                generate(name)

    def test_no_patient_contact_event_or_task_seed(self):
        sql = generate('ihms_smtp_test_unit')
        inserts = re.findall(r'INSERT(?: IGNORE)? INTO `?(\w+)', sql)
        self.assertEqual(set(inserts), {'user', 'doctor', 'doctor_consultation', 'health_model_config',
                                      'health_alert_rule', 'health_reference_activation'})
        self.assertNotIn('DROP TABLE', sql)
        self.assertNotIn('USE `personal_health`', sql)
        self.assertIn('CREATE DATABASE `ihms_smtp_test_unit`', sql)
        self.assertNotIn('CREATE DATABASE IF NOT EXISTS', sql)

    def test_mysql_schema_has_latest_columns_and_null_sos_measurements(self):
        sql = generate('ihms_smtp_test_unit')
        self.assertNotIn('CLOB', sql)
        self.assertNotIn('ADD COLUMN IF NOT EXISTS', sql)
        self.assertIn('`request_id` VARCHAR(36)', sql)
        self.assertIn('first_measurement_id BIGINT NULL', sql)
        self.assertIn('measurement_id BIGINT NULL', sql)
        self.assertIn('priority INT NOT NULL DEFAULT 0', sql)
        self.assertIn('uk_health_notice_stage', sql)
        self.assertIn('CREATE TABLE `health_sos_reminder`', sql)
        self.assertIn('CREATE TABLE `health_reference_activation`', sql)


if __name__ == '__main__':
    unittest.main()
