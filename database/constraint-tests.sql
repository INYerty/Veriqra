-- Disposable seeded database only; mutations roll back individually.
-- Expected acceptance records a Service invariant, not a valid business operation.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SESSION time_zone = '+00:00';
SET SESSION sql_mode = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';
CREATE TEMPORARY TABLE qt_test_results (
  test_no INT AUTO_INCREMENT PRIMARY KEY,
  test_name VARCHAR(160) NOT NULL,
  expected_errno INT NOT NULL,
  actual_errno INT NOT NULL,
  outcome VARCHAR(8) NOT NULL,
  detail TEXT
) ENGINE=InnoDB;
DELIMITER $$
CREATE PROCEDURE qt_assert_sql(IN p_name VARCHAR(160), IN p_sql TEXT, IN p_expected INT, IN p_constraint VARCHAR(64))
BEGIN
  DECLARE v_errno INT DEFAULT 0;
  DECLARE v_message TEXT DEFAULT '';
  START TRANSACTION;
  BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
      GET DIAGNOSTICS CONDITION 1 v_errno = MYSQL_ERRNO, v_message = MESSAGE_TEXT;
    END;
    SET @qt_sql = p_sql;
    PREPARE qt_statement FROM @qt_sql;
    EXECUTE qt_statement;
    DEALLOCATE PREPARE qt_statement;
  END;
  ROLLBACK;
  INSERT INTO qt_test_results(test_name,expected_errno,actual_errno,outcome,detail)
  VALUES(p_name,p_expected,v_errno,
    IF(v_errno=p_expected AND (p_constraint='' OR LOCATE(p_constraint,v_message)>0),'PASS','FAIL'),v_message);
END$$
CREATE PROCEDURE qt_assert_value(IN p_name VARCHAR(160), IN p_value BIGINT, IN p_expected BIGINT)
BEGIN
  INSERT INTO qt_test_results(test_name,expected_errno,actual_errno,outcome,detail)
  VALUES(p_name,0,0,IF(p_value <=> p_expected,'PASS','FAIL'),
    CONCAT('expected=',p_expected,', actual=',COALESCE(p_value,'NULL')));
END$$
CREATE PROCEDURE qt_finish()
BEGIN
  DECLARE v_failures INT;
  SELECT COUNT(*) INTO v_failures FROM qt_test_results WHERE outcome='FAIL';
  IF v_failures<>0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Veriqra constraint validation failed; inspect the test output';
  END IF;
END$$
DELIMITER ;

CALL qt_assert_sql('CHECK ck_users_username_nonblank','UPDATE `users` SET `username`=''   '' WHERE `id`=1',3819,'ck_users_username_nonblank');
CALL qt_assert_sql('CHECK ck_users_display_name_nonblank','UPDATE `users` SET `display_name`=''   '' WHERE `id`=1',3819,'ck_users_display_name_nonblank');
CALL qt_assert_sql('CHECK ck_users_password_hash_nonblank','UPDATE `users` SET `password_hash`=''   '' WHERE `id`=1',3819,'ck_users_password_hash_nonblank');
CALL qt_assert_sql('CHECK ck_users_system_role','UPDATE `users` SET `system_role`=''INVALID'' WHERE `id`=1',3819,'ck_users_system_role');
CALL qt_assert_sql('CHECK ck_users_status','UPDATE `users` SET `status`=''INVALID'' WHERE `id`=1',3819,'ck_users_status');
CALL qt_assert_sql('FK projects.created_by','UPDATE `projects` SET `created_by`=999999999 WHERE `id`=1',1452,'fk_projects_created_by');
CALL qt_assert_sql('CHECK ck_projects_name_nonblank','UPDATE `projects` SET `name`=''   '' WHERE `id`=1',3819,'ck_projects_name_nonblank');
CALL qt_assert_sql('CHECK ck_projects_status','UPDATE `projects` SET `status`=''INVALID'' WHERE `id`=1',3819,'ck_projects_status');
CALL qt_assert_sql('FK project_members.project_id','UPDATE `project_members` SET `project_id`=999999999 WHERE `project_id`=1 AND `user_id`=2',1452,'fk_project_members_project_id');
CALL qt_assert_sql('FK project_members.user_id','UPDATE `project_members` SET `user_id`=999999999 WHERE `project_id`=1 AND `user_id`=2',1452,'fk_project_members_user_id');
CALL qt_assert_sql('CHECK ck_project_members_project_role','UPDATE `project_members` SET `project_role`=''INVALID'' WHERE `project_id`=1 AND `user_id`=2',3819,'ck_project_members_project_role');
CALL qt_assert_sql('CHECK ck_project_members_status','UPDATE `project_members` SET `status`=''INVALID'' WHERE `project_id`=1 AND `user_id`=2',3819,'ck_project_members_status');
CALL qt_assert_sql('FK project_counters.project_id','UPDATE `project_counters` SET `project_id`=999999999 WHERE `project_id`=1 AND `entity_type`=''REQ''',1452,'fk_project_counters_project_id');
CALL qt_assert_sql('CHECK ck_project_counters_entity_type','UPDATE `project_counters` SET `entity_type`=''INVALID'' WHERE `project_id`=1 AND `entity_type`=''REQ''',3819,'ck_project_counters_entity_type');
CALL qt_assert_sql('CHECK ck_project_counters_next_value','UPDATE `project_counters` SET `next_value`=0 WHERE `project_id`=1 AND `entity_type`=''REQ''',3819,'ck_project_counters_next_value');
CALL qt_assert_sql('FK requirements.project_id','UPDATE `requirements` SET `project_id`=999999999 WHERE `id`=101',1452,'fk_requirements_project_id');
CALL qt_assert_sql('FK requirements.created_by','UPDATE `requirements` SET `created_by`=999999999 WHERE `id`=101',1452,'fk_requirements_created_by');
CALL qt_assert_sql('CHECK ck_requirements_key_no','UPDATE `requirements` SET `key_no`=0 WHERE `id`=101',3819,'ck_requirements_key_no');
CALL qt_assert_sql('CHECK ck_requirements_title_nonblank','UPDATE `requirements` SET `title`=''   '' WHERE `id`=101',3819,'ck_requirements_title_nonblank');
CALL qt_assert_sql('CHECK ck_requirements_priority','UPDATE `requirements` SET `priority`=''INVALID'' WHERE `id`=101',3819,'ck_requirements_priority');
CALL qt_assert_sql('CHECK ck_requirements_status','UPDATE `requirements` SET `status`=''INVALID'' WHERE `id`=101',3819,'ck_requirements_status');
CALL qt_assert_sql('FK test_cases.project_id','UPDATE `test_cases` SET `project_id`=999999999 WHERE `id`=101',1452,'fk_test_cases_project_id');
CALL qt_assert_sql('FK test_cases.created_by','UPDATE `test_cases` SET `created_by`=999999999 WHERE `id`=101',1452,'fk_test_cases_created_by');
CALL qt_assert_sql('CHECK ck_test_cases_key_no','UPDATE `test_cases` SET `key_no`=0 WHERE `id`=101',3819,'ck_test_cases_key_no');
CALL qt_assert_sql('CHECK ck_test_cases_title_nonblank','UPDATE `test_cases` SET `title`=''   '' WHERE `id`=101',3819,'ck_test_cases_title_nonblank');
CALL qt_assert_sql('CHECK ck_test_cases_priority','UPDATE `test_cases` SET `priority`=''INVALID'' WHERE `id`=101',3819,'ck_test_cases_priority');
CALL qt_assert_sql('CHECK ck_test_cases_status','UPDATE `test_cases` SET `status`=''INVALID'' WHERE `id`=101',3819,'ck_test_cases_status');
CALL qt_assert_sql('FK test_automation_identities.project_id','UPDATE `test_automation_identities` SET `project_id`=999999999 WHERE `id`=101',1452,'fk_test_automation_identities_project_id');
CALL qt_assert_sql('CHECK ck_test_automation_identities_source','UPDATE `test_automation_identities` SET `source`=''INVALID'' WHERE `id`=101',3819,'ck_test_automation_identities_source');
CALL qt_assert_sql('CHECK ck_test_automation_identities_namespace_nonblank','UPDATE `test_automation_identities` SET `namespace`=''   '' WHERE `id`=101',3819,'ck_test_automation_identities_namespace_nonblank');
CALL qt_assert_sql('CHECK ck_automation_external_key_nonempty','UPDATE `test_automation_identities` SET `external_key`='''' WHERE `id`=101',3819,'ck_automation_external_key_nonempty');
CALL qt_assert_sql('FK test_automation_mappings.automation_identity_id','UPDATE `test_automation_mappings` SET `automation_identity_id`=999999999 WHERE `id`=101',1452,'fk_test_automation_mappings_automation_identity_id');
CALL qt_assert_sql('FK test_automation_mappings.test_case_id','UPDATE `test_automation_mappings` SET `test_case_id`=999999999 WHERE `id`=101',1452,'fk_test_automation_mappings_test_case_id');
CALL qt_assert_sql('FK test_automation_mappings.created_by','UPDATE `test_automation_mappings` SET `created_by`=999999999 WHERE `id`=101',1452,'fk_test_automation_mappings_created_by');
CALL qt_assert_sql('CHECK ck_test_automation_mappings_status','UPDATE `test_automation_mappings` SET `status`=''INVALID'' WHERE `id`=101',3819,'ck_test_automation_mappings_status');
CALL qt_assert_sql('FK test_steps.test_case_id','UPDATE `test_steps` SET `test_case_id`=999999999 WHERE `test_case_id`=101 AND `step_order`=1',1452,'fk_test_steps_test_case_id');
CALL qt_assert_sql('CHECK ck_test_steps_step_order','UPDATE `test_steps` SET `step_order`=0 WHERE `test_case_id`=101 AND `step_order`=1',3819,'ck_test_steps_step_order');
CALL qt_assert_sql('CHECK ck_test_steps_action_nonblank','UPDATE `test_steps` SET `action`=''   '' WHERE `test_case_id`=101 AND `step_order`=1',3819,'ck_test_steps_action_nonblank');
CALL qt_assert_sql('CHECK ck_test_steps_expected_result_nonblank','UPDATE `test_steps` SET `expected_result`=''   '' WHERE `test_case_id`=101 AND `step_order`=1',3819,'ck_test_steps_expected_result_nonblank');
CALL qt_assert_sql('FK test_case_requirements.requirement_id','UPDATE `test_case_requirements` SET `requirement_id`=999999999 WHERE `requirement_id`=101 AND `test_case_id`=101',1452,'fk_test_case_requirements_requirement_id');
CALL qt_assert_sql('FK test_case_requirements.test_case_id','UPDATE `test_case_requirements` SET `test_case_id`=999999999 WHERE `requirement_id`=101 AND `test_case_id`=101',1452,'fk_test_case_requirements_test_case_id');
CALL qt_assert_sql('FK test_case_requirements.linked_by','UPDATE `test_case_requirements` SET `linked_by`=999999999 WHERE `requirement_id`=101 AND `test_case_id`=101',1452,'fk_test_case_requirements_linked_by');
CALL qt_assert_sql('FK test_case_requirements.reviewed_by','UPDATE `test_case_requirements` SET `reviewed_by`=999999999 WHERE `requirement_id`=101 AND `test_case_id`=101',1452,'fk_test_case_requirements_reviewed_by');
CALL qt_assert_sql('CHECK ck_test_case_requirements_status','UPDATE `test_case_requirements` SET `status`=''INVALID'' WHERE `requirement_id`=101 AND `test_case_id`=101',3819,'ck_test_case_requirements_status');
CALL qt_assert_sql('CHECK ck_trace_review_shape','UPDATE `test_case_requirements` SET `reviewed_at`=NULL WHERE `requirement_id`=101 AND `test_case_id`=101',3819,'ck_trace_review_shape');
CALL qt_assert_sql('FK test_plans.project_id','UPDATE `test_plans` SET `project_id`=999999999 WHERE `id`=101',1452,'fk_test_plans_project_id');
CALL qt_assert_sql('FK test_plans.created_by','UPDATE `test_plans` SET `created_by`=999999999 WHERE `id`=101',1452,'fk_test_plans_created_by');
CALL qt_assert_sql('CHECK ck_test_plans_key_no','UPDATE `test_plans` SET `key_no`=0 WHERE `id`=101',3819,'ck_test_plans_key_no');
CALL qt_assert_sql('CHECK ck_test_plans_name_nonblank','UPDATE `test_plans` SET `name`=''   '' WHERE `id`=101',3819,'ck_test_plans_name_nonblank');
CALL qt_assert_sql('CHECK ck_test_plans_status','UPDATE `test_plans` SET `status`=''INVALID'' WHERE `id`=101',3819,'ck_test_plans_status');
CALL qt_assert_sql('FK test_plan_cases.test_plan_id','UPDATE `test_plan_cases` SET `test_plan_id`=999999999 WHERE `test_plan_id`=101 AND `test_case_id`=101',1452,'fk_test_plan_cases_test_plan_id');
CALL qt_assert_sql('FK test_plan_cases.test_case_id','UPDATE `test_plan_cases` SET `test_case_id`=999999999 WHERE `test_plan_id`=101 AND `test_case_id`=101',1452,'fk_test_plan_cases_test_case_id');
CALL qt_assert_sql('FK test_plan_cases.added_by','UPDATE `test_plan_cases` SET `added_by`=999999999 WHERE `test_plan_id`=101 AND `test_case_id`=101',1452,'fk_test_plan_cases_added_by');
CALL qt_assert_sql('FK test_runs.project_id','UPDATE `test_runs` SET `project_id`=999999999 WHERE `id`=101',1452,'fk_test_runs_project_id');
CALL qt_assert_sql('FK test_runs.test_plan_id','UPDATE `test_runs` SET `test_plan_id`=999999999 WHERE `id`=101',1452,'fk_test_runs_test_plan_id');
CALL qt_assert_sql('FK test_runs.created_by','UPDATE `test_runs` SET `created_by`=999999999 WHERE `id`=101',1452,'fk_test_runs_created_by');
CALL qt_assert_sql('CHECK ck_test_runs_name_nonblank','UPDATE `test_runs` SET `name`=''   '' WHERE `id`=101',3819,'ck_test_runs_name_nonblank');
CALL qt_assert_sql('CHECK ck_test_runs_status','UPDATE `test_runs` SET `status`=''INVALID'' WHERE `id`=101',3819,'ck_test_runs_status');
CALL qt_assert_sql('CHECK ck_runs_ended_at','UPDATE `test_runs` SET `ended_at`=''2026-09-06 12:00:00'' WHERE `id`=101',3819,'ck_runs_ended_at');
CALL qt_assert_sql('FK test_run_cases.test_run_id','UPDATE `test_run_cases` SET `test_run_id`=999999999 WHERE `id`=101',1452,'fk_test_run_cases_test_run_id');
CALL qt_assert_sql('FK test_run_cases.test_case_id','UPDATE `test_run_cases` SET `test_case_id`=999999999 WHERE `id`=101',1452,'fk_test_run_cases_test_case_id');
CALL qt_assert_sql('CHECK ck_test_run_cases_snapshot_title_nonblank','UPDATE `test_run_cases` SET `snapshot_title`=''   '' WHERE `id`=101',3819,'ck_test_run_cases_snapshot_title_nonblank');
CALL qt_assert_sql('CHECK ck_test_run_cases_snapshot_priority','UPDATE `test_run_cases` SET `snapshot_priority`=''INVALID'' WHERE `id`=101',3819,'ck_test_run_cases_snapshot_priority');
CALL qt_assert_sql('FK test_run_case_steps.test_run_case_id','UPDATE `test_run_case_steps` SET `test_run_case_id`=999999999 WHERE `test_run_case_id`=101 AND `step_order`=1',1452,'fk_test_run_case_steps_test_run_case_id');
CALL qt_assert_sql('CHECK ck_test_run_case_steps_step_order','UPDATE `test_run_case_steps` SET `step_order`=0 WHERE `test_run_case_id`=101 AND `step_order`=1',3819,'ck_test_run_case_steps_step_order');
CALL qt_assert_sql('CHECK ck_test_run_case_steps_action_nonblank','UPDATE `test_run_case_steps` SET `action`=''   '' WHERE `test_run_case_id`=101 AND `step_order`=1',3819,'ck_test_run_case_steps_action_nonblank');
CALL qt_assert_sql('CHECK ck_test_run_case_steps_expected_result_nonblank','UPDATE `test_run_case_steps` SET `expected_result`=''   '' WHERE `test_run_case_id`=101 AND `step_order`=1',3819,'ck_test_run_case_steps_expected_result_nonblank');
CALL qt_assert_sql('FK test_imports.test_run_id','UPDATE `test_imports` SET `test_run_id`=999999999 WHERE `id`=101',1452,'fk_test_imports_test_run_id');
CALL qt_assert_sql('FK test_imports.imported_by','UPDATE `test_imports` SET `imported_by`=999999999 WHERE `id`=101',1452,'fk_test_imports_imported_by');
CALL qt_assert_sql('CHECK ck_test_imports_source_namespace_nonblank','UPDATE `test_imports` SET `source_namespace`=''   '' WHERE `id`=101',3819,'ck_test_imports_source_namespace_nonblank');
CALL qt_assert_sql('CHECK ck_test_imports_original_filename_nonblank','UPDATE `test_imports` SET `original_filename`=''   '' WHERE `id`=101',3819,'ck_test_imports_original_filename_nonblank');
CALL qt_assert_sql('FK test_attempts.test_run_case_id','UPDATE `test_attempts` SET `test_run_case_id`=999999999 WHERE `id`=101',1452,'fk_test_attempts_test_run_case_id');
CALL qt_assert_sql('FK test_attempts.executed_by','UPDATE `test_attempts` SET `executed_by`=999999999 WHERE `id`=102',1452,'fk_test_attempts_executed_by');
CALL qt_assert_sql('FK test_attempts.import_id','UPDATE `test_attempts` SET `import_id`=999999999 WHERE `id`=101',1452,'fk_test_attempts_import_id');
CALL qt_assert_sql('FK test_attempts.automation_mapping_id','UPDATE `test_attempts` SET `automation_mapping_id`=999999999 WHERE `id`=101',1452,'fk_test_attempts_automation_mapping_id');
CALL qt_assert_sql('CHECK ck_test_attempts_attempt_no','UPDATE `test_attempts` SET `attempt_no`=0 WHERE `id`=101',3819,'ck_test_attempts_attempt_no');
CALL qt_assert_sql('CHECK ck_test_attempts_status','UPDATE `test_attempts` SET `status`=''INVALID'' WHERE `id`=105',3819,'ck_test_attempts_status');
CALL qt_assert_sql('CHECK ck_attempts_failure_message','UPDATE `test_attempts` SET `status`=''PASS'' WHERE `id`=101',3819,'ck_attempts_failure_message');
CALL qt_assert_sql('CHECK ck_attempts_source_shape','UPDATE `test_attempts` SET `executed_by`=2 WHERE `id`=101',3819,'ck_attempts_source_shape');
CALL qt_assert_sql('FK defects.project_id','UPDATE `defects` SET `project_id`=999999999 WHERE `id`=101',1452,'fk_defects_project_id');
CALL qt_assert_sql('FK defects.reporter_id','UPDATE `defects` SET `reporter_id`=999999999 WHERE `id`=101',1452,'fk_defects_reporter_id');
CALL qt_assert_sql('FK defects.assignee_id','UPDATE `defects` SET `assignee_id`=999999999 WHERE `id`=101',1452,'fk_defects_assignee_id');
CALL qt_assert_sql('CHECK ck_defects_key_no','UPDATE `defects` SET `key_no`=0 WHERE `id`=101',3819,'ck_defects_key_no');
CALL qt_assert_sql('CHECK ck_defects_title_nonblank','UPDATE `defects` SET `title`=''   '' WHERE `id`=101',3819,'ck_defects_title_nonblank');
CALL qt_assert_sql('CHECK ck_defects_severity','UPDATE `defects` SET `severity`=''INVALID'' WHERE `id`=101',3819,'ck_defects_severity');
CALL qt_assert_sql('CHECK ck_defects_priority','UPDATE `defects` SET `priority`=''INVALID'' WHERE `id`=101',3819,'ck_defects_priority');
CALL qt_assert_sql('CHECK ck_defects_status','UPDATE `defects` SET `status`=''INVALID'' WHERE `id`=101',3819,'ck_defects_status');
CALL qt_assert_sql('CHECK ck_defects_resolution_note','UPDATE `defects` SET `status`=''CLOSED'' WHERE `id`=101',3819,'ck_defects_resolution_note');
CALL qt_assert_sql('FK test_attempt_defects.attempt_id','UPDATE `test_attempt_defects` SET `attempt_id`=999999999 WHERE `attempt_id`=101 AND `defect_id`=101',1452,'fk_test_attempt_defects_attempt_id');
CALL qt_assert_sql('FK test_attempt_defects.defect_id','UPDATE `test_attempt_defects` SET `defect_id`=999999999 WHERE `attempt_id`=101 AND `defect_id`=101',1452,'fk_test_attempt_defects_defect_id');
CALL qt_assert_sql('FK test_attempt_defects.linked_by','UPDATE `test_attempt_defects` SET `linked_by`=999999999 WHERE `attempt_id`=101 AND `defect_id`=101',1452,'fk_test_attempt_defects_linked_by');
CALL qt_assert_sql('UNIQUE uq_users_username','UPDATE `users` SET `username`=''qa_admin'' WHERE `id`=2',1062,'uq_users_username');
CALL qt_assert_sql('UNIQUE uq_projects_key','UPDATE `projects` SET `project_key`=''QA1'' WHERE `id`=2',1062,'uq_projects_key');
CALL qt_assert_sql('UNIQUE uq_requirements_project_key','UPDATE `requirements` SET `project_id`=1, `key_no`=1 WHERE `id`=102',1062,'uq_requirements_project_key');
CALL qt_assert_sql('UNIQUE uq_test_cases_project_key','UPDATE `test_cases` SET `project_id`=1, `key_no`=1 WHERE `id`=102',1062,'uq_test_cases_project_key');
CALL qt_assert_sql('UNIQUE uq_automation_identity','UPDATE `test_automation_identities` SET `project_id`=1, `source`=DEFAULT, `namespace`=''web'', `external_key`=''v1|12:qa.LoginTest|16:test_valid_login'' WHERE `id`=102',1062,'uq_automation_identity');
CALL qt_assert_sql('UNIQUE uq_automation_mapping_identity','UPDATE `test_automation_mappings` SET `automation_identity_id`=101 WHERE `id`=102',1062,'uq_automation_mapping_identity');
CALL qt_assert_sql('UNIQUE uq_test_plans_project_key','UPDATE `test_plans` SET `project_id`=1, `key_no`=1 WHERE `id`=102',1062,'uq_test_plans_project_key');
CALL qt_assert_sql('UNIQUE uq_run_cases_run_case','UPDATE `test_run_cases` SET `test_run_id`=101, `test_case_id`=101 WHERE `id`=102',1062,'uq_run_cases_run_case');
CALL qt_assert_sql('UNIQUE uq_imports_request','UPDATE `test_imports` SET `request_key`=UNHEX(''00000000000000000000000000018705'') WHERE `id`=102',1062,'uq_imports_request');
CALL qt_assert_sql('UNIQUE uq_attempts_run_case_no','UPDATE `test_attempts` SET `test_run_case_id`=101, `attempt_no`=1 WHERE `id`=102',1062,'uq_attempts_run_case_no');
CALL qt_assert_sql('UNIQUE uq_attempts_submission','UPDATE `test_attempts` SET `submission_key`=UNHEX(''00000000000000000000000000030da5'') WHERE `id`=102',1062,'uq_attempts_submission');
CALL qt_assert_sql('UNIQUE uq_attempts_import_run_case','UPDATE test_attempts SET test_run_case_id=101, attempt_no=3 WHERE id=103',1062,'uq_attempts_import_run_case');
CALL qt_assert_sql('UNIQUE uq_defects_project_key','UPDATE `defects` SET `project_id`=1, `key_no`=1 WHERE `id`=102',1062,'uq_defects_project_key');
CALL qt_assert_sql('DELETE RESTRICT users','DELETE FROM `users` WHERE `id`=1',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT users','UPDATE `users` SET id=999999999 WHERE `id`=1',1451,'');
CALL qt_assert_sql('DELETE RESTRICT projects','DELETE FROM `projects` WHERE `id`=1',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT projects','UPDATE `projects` SET id=999999999 WHERE `id`=1',1451,'');
CALL qt_assert_sql('DELETE RESTRICT test_automation_identities','DELETE FROM `test_automation_identities` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT test_automation_identities','UPDATE `test_automation_identities` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('DELETE RESTRICT test_cases','DELETE FROM `test_cases` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT test_cases','UPDATE `test_cases` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('DELETE RESTRICT requirements','DELETE FROM `requirements` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT requirements','UPDATE `requirements` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('DELETE RESTRICT test_plans','DELETE FROM `test_plans` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT test_plans','UPDATE `test_plans` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('DELETE RESTRICT test_runs','DELETE FROM `test_runs` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT test_runs','UPDATE `test_runs` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('DELETE RESTRICT test_run_cases','DELETE FROM `test_run_cases` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT test_run_cases','UPDATE `test_run_cases` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('DELETE RESTRICT test_imports','DELETE FROM `test_imports` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT test_imports','UPDATE `test_imports` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('DELETE RESTRICT test_automation_mappings','DELETE FROM `test_automation_mappings` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT test_automation_mappings','UPDATE `test_automation_mappings` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('DELETE RESTRICT test_attempts','DELETE FROM `test_attempts` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT test_attempts','UPDATE `test_attempts` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('DELETE RESTRICT defects','DELETE FROM `defects` WHERE `id`=101',1451,'');
CALL qt_assert_sql('UPDATE RESTRICT defects','UPDATE `defects` SET id=999999999 WHERE `id`=101',1451,'');
CALL qt_assert_sql('Run requires project','UPDATE test_runs SET project_id=NULL WHERE id=101',1048,'');
CALL qt_assert_sql('Planless Run permitted','UPDATE test_runs SET test_plan_id=NULL WHERE id=101',0,'');
CALL qt_assert_sql('Completed Run requires ended_at','UPDATE test_runs SET ended_at=NULL WHERE id=103',3819,'ck_runs_ended_at');
CALL qt_assert_sql('Identity overlength rejected','UPDATE test_automation_identities SET external_key=REPEAT(''x'',513) WHERE id=103',1406,'');
CALL qt_assert_sql('Same business number across projects','UPDATE requirements SET key_no=1 WHERE id=201',0,'');
CALL qt_assert_sql('Same external identity across projects','UPDATE test_automation_identities SET external_key=(SELECT external_key FROM (SELECT external_key FROM test_automation_identities WHERE id=101) a) WHERE id=201',0,'');
CALL qt_assert_sql('Identity is case sensitive','UPDATE test_automation_identities SET external_key=(SELECT UPPER(external_key) FROM (SELECT external_key FROM test_automation_identities WHERE id=101) a) WHERE id=102',0,'');
CALL qt_assert_sql('Manual attempts allow NULL import','INSERT INTO test_attempts (test_run_case_id,attempt_no,status,executed_by,submission_key) VALUES (101,3,''PASS'',2,UNHEX(''eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee''))',0,'');
CALL qt_assert_sql('SKIPPED accepted','UPDATE test_attempts SET status=''SKIPPED'' WHERE id=105',0,'');
CALL qt_assert_sql('NOT_RUN not an Attempt','UPDATE test_attempts SET status=''NOT_RUN'' WHERE id=105',3819,'ck_test_attempts_status');
CALL qt_assert_sql('Service invariant: Requirement-Case same project','UPDATE test_case_requirements SET test_case_id=201 WHERE requirement_id=101 AND test_case_id=102',0,'');
CALL qt_assert_sql('Service invariant: Plan-Case same project','UPDATE test_plan_cases SET test_case_id=201 WHERE test_plan_id=101 AND test_case_id=102',0,'');
CALL qt_assert_sql('Service invariant: Run-Plan same project','UPDATE test_runs SET test_plan_id=201 WHERE id=101',0,'');
CALL qt_assert_sql('Service invariant: RunCase-Case same project','UPDATE test_run_cases SET test_case_id=203 WHERE id=103',0,'');
CALL qt_assert_sql('Service invariant: Identity-Case same project','UPDATE test_automation_mappings SET test_case_id=201 WHERE id=103',0,'');
CALL qt_assert_sql('Service invariant: Attempt-Import same Run','UPDATE test_attempts SET import_id=201 WHERE id=101',0,'');
CALL qt_assert_sql('Service invariant: Attempt-Mapping same Case','UPDATE test_attempts SET automation_mapping_id=201 WHERE id=101',0,'');
CALL qt_assert_sql('Service invariant: Attempt-Defect same project','UPDATE test_attempt_defects SET defect_id=201 WHERE attempt_id=101 AND defect_id=101',0,'');
CALL qt_assert_sql('Service invariant: Import namespace matches identity','UPDATE test_imports SET source_namespace=''other'' WHERE id=101',0,'');
CALL qt_assert_sql('Service invariant: historical snapshot immutable','UPDATE test_run_cases SET snapshot_title=''Changed evidence'' WHERE id=101',0,'');
CALL qt_assert_sql('Service invariant: Attempt immutable','UPDATE test_attempts SET comment=''Changed evidence'' WHERE id=101',0,'');
CALL qt_assert_sql('Service invariant: completed Run rejects new Attempt','INSERT INTO test_attempts (test_run_case_id,attempt_no,status,executed_by,submission_key) VALUES (105,2,''PASS'',2,UNHEX(''dddddddddddddddddddddddddddddddd''))',0,'');
CALL qt_assert_sql('Service invariant: only FAIL links defect','UPDATE test_attempt_defects SET attempt_id=102 WHERE attempt_id=101 AND defect_id=101',0,'');
CALL qt_assert_sql('Service invariant: assignee project role','UPDATE defects SET assignee_id=3 WHERE id=101',0,'');
CALL qt_assert_sql('Identity preserves trailing space','UPDATE test_automation_identities SET external_key=(SELECT CONCAT(external_key,'' '') FROM (SELECT external_key FROM test_automation_identities WHERE id=101) a) WHERE id=102',0,'');
CALL qt_assert_sql('Service invariant: used mapping cannot rebind','UPDATE test_automation_mappings SET test_case_id=102 WHERE id=101',0,'');
CALL qt_assert_value('seed count users',(SELECT COUNT(*) FROM `users`),10);
CALL qt_assert_value('seed count projects',(SELECT COUNT(*) FROM `projects`),10);
CALL qt_assert_value('seed count project_members',(SELECT COUNT(*) FROM `project_members`),20);
CALL qt_assert_value('seed count project_counters',(SELECT COUNT(*) FROM `project_counters`),40);
CALL qt_assert_value('seed count requirements',(SELECT COUNT(*) FROM `requirements`),30);
CALL qt_assert_value('seed count test_cases',(SELECT COUNT(*) FROM `test_cases`),40);
CALL qt_assert_value('seed count test_automation_identities',(SELECT COUNT(*) FROM `test_automation_identities`),30);
CALL qt_assert_value('seed count test_automation_mappings',(SELECT COUNT(*) FROM `test_automation_mappings`),30);
CALL qt_assert_value('seed count test_steps',(SELECT COUNT(*) FROM `test_steps`),80);
CALL qt_assert_value('seed count test_case_requirements',(SELECT COUNT(*) FROM `test_case_requirements`),40);
CALL qt_assert_value('seed count test_plans',(SELECT COUNT(*) FROM `test_plans`),20);
CALL qt_assert_value('seed count test_plan_cases',(SELECT COUNT(*) FROM `test_plan_cases`),50);
CALL qt_assert_value('seed count test_runs',(SELECT COUNT(*) FROM `test_runs`),30);
CALL qt_assert_value('seed count test_run_cases',(SELECT COUNT(*) FROM `test_run_cases`),50);
CALL qt_assert_value('seed count test_run_case_steps',(SELECT COUNT(*) FROM `test_run_case_steps`),100);
CALL qt_assert_value('seed count test_imports',(SELECT COUNT(*) FROM `test_imports`),20);
CALL qt_assert_value('seed count test_attempts',(SELECT COUNT(*) FROM `test_attempts`),50);
CALL qt_assert_value('seed count defects',(SELECT COUNT(*) FROM `defects`),20);
CALL qt_assert_value('seed count test_attempt_defects',(SELECT COUNT(*) FROM `test_attempt_defects`),10);
CALL qt_assert_value('FAIL then PASS history',(SELECT COUNT(*) FROM test_attempts WHERE test_run_case_id=101),2);
CALL qt_assert_value('FAIL evidence retained',(SELECT COUNT(*) FROM test_attempt_defects WHERE attempt_id=101 AND defect_id=101),1);
CALL qt_assert_value('Latest attempt is PASS',(SELECT status='PASS' FROM test_attempts WHERE test_run_case_id=101 ORDER BY attempt_no DESC LIMIT 1),1);
CALL qt_assert_value('NOT_RUN is absent Attempt',(SELECT COUNT(*) FROM test_attempts WHERE test_run_case_id=103),0);
CALL qt_assert_value('All SKIPPED Run is completed',(SELECT status='COMPLETED' FROM test_runs WHERE id=103),1);

SELECT test_no,test_name,expected_errno,actual_errno,outcome,detail FROM qt_test_results ORDER BY test_no;
SELECT COUNT(*) AS total_tests, SUM(outcome='PASS') AS passed, SUM(outcome='FAIL') AS failed FROM qt_test_results;
CALL qt_finish();
DROP PROCEDURE qt_finish;
DROP PROCEDURE qt_assert_value;
DROP PROCEDURE qt_assert_sql;
DROP TEMPORARY TABLE qt_test_results;
