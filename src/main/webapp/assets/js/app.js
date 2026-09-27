(function ($, api) {
  'use strict';
  const t = window.I18n.t;
  const projectStorageKey = 'veriqra.selectedProjectId';
  const shell = $('#app-shell');
  const splash = $('#bootstrap-screen');
  const picker = $('#project-select');
  const alert = $('#page-alert');
  let loading = false;
  let selecting = false;
  let currentUser = null;
  let projects = [];
  let activeProject = null;
  let selectedId = null;
  let currentView = 'dashboard';

  function announce(name, detail) { document.dispatchEvent(new CustomEvent('veriqra:' + name, { detail: detail })); }
  function setView(view, silent) {
    currentView = ['dashboard', 'requirements', 'test-cases', 'test-plans', 'runs', 'defects', 'automation', 'collaboration'].includes(view) ? view : 'dashboard';
    $('[data-view]').removeClass('active').removeAttr('aria-current');
    $('[data-view="' + currentView + '"]').addClass('active').attr('aria-current', 'page');
    const labels = { dashboard: [t("shell.overview", null, 'OVERVIEW'), t("shell.projectDashboard", null, 'Project dashboard'), t("shell.yourCurrentQualityWorkspace", null, 'Your current quality workspace.')], requirements: [t("shell.testASSETS", null, 'TEST ASSETS'), t("common.requirements", null, 'Requirements'), t("shell.manageRequirementsAndTheirTestCoverage", null, 'Manage requirements and their test coverage.')], 'test-cases': [t("shell.testASSETS", null, 'TEST ASSETS'), t("common.testCases", null, 'Test cases'), t("shell.maintainCurrentTestDefinitionsAndSteps", null, 'Maintain current test definitions and steps.')], 'test-plans': [t("shell.planning", null, 'PLANNING'), t("common.testPlans", null, 'Test plans'), t("shell.defineTheCasesToExecute", null, 'Define the cases to execute.')], runs: [t("shell.execution", null, 'EXECUTION'), t("common.testRuns", null, 'Test runs'), t("shell.inspectFrozenSnapshotsAndRecordAttempts", null, 'Inspect frozen snapshots and record attempts.')], defects: [t("shell.quality", null, 'QUALITY'), t("common.defects", null, 'Defects'), t("shell.trackFailuresRetestsAndClosureEvidence", null, 'Track failures, retests and closure evidence.')], automation: [t("shell.automation", null, 'AUTOMATION'), t("common.automationImports", null, 'Automation & imports'), t("shell.confirmMappingsAndImportJUnitResults", null, 'Confirm mappings and import JUnit results.')], collaboration: [t('collab.eyebrow', null, 'COLLABORATION'), t('collab.navigation', null, 'Teams & tasks'), t('collab.description', null, 'Organize project teams and review assigned work.')] };
    $('#view-eyebrow').text(labels[currentView][0]);
    $('#view-title').text(labels[currentView][1]);
    $('#view-description').text(labels[currentView][2]);
    document.title = labels[currentView][1] + ' · Veriqra';
    $('#project-dashboard').toggleClass('d-none', currentView !== 'dashboard' || !selectedId);
    $('#asset-workspace').toggleClass('d-none', !['requirements', 'test-cases'].includes(currentView) || !selectedId);
    $('#execution-workspace').toggleClass('d-none', !['test-plans', 'runs'].includes(currentView) || !selectedId);
    $('#defect-workspace').toggleClass('d-none', currentView !== 'defects' || !selectedId);
    $('#automation-workspace').toggleClass('d-none', currentView !== 'automation' || !selectedId);
    $('#collaboration-workspace').toggleClass('d-none', currentView !== 'collaboration' || !selectedId);
    $('#sidebar').removeClass('open');
    $('#sidebar-toggle').attr('aria-expanded', 'false');
    if (!silent) announce('view', { view: currentView, projectId: selectedId });
  }

  function savedProject() { try { return sessionStorage.getItem(projectStorageKey); } catch (_) { return null; } }
  function rememberProject(id) { try { if (id) sessionStorage.setItem(projectStorageKey, id); else sessionStorage.removeItem(projectStorageKey); } catch (_) { /* Optional UI preference. */ } }
  function feedback(message) { alert.text(message || '').toggleClass('d-none', !message); }
  function showShell() { splash.addClass('d-none'); shell.removeClass('d-none'); }
  function clearProjectContext() {
    announce('project', { project: null });
    projects = [];
    activeProject = null;
    selectedId = null;
    picker.empty().append($('<option>').val('').text(t("shell.loadingProjects", null, 'Loading projects…'))).prop('disabled', true);
    $('#project-dashboard, #empty-projects, #asset-workspace, #execution-workspace, #defect-workspace, #automation-workspace, #collaboration-workspace').addClass('d-none');
    $('#project-key, #project-title, #project-description, #project-status, #account-name').text('');
  }
  function showProject(project) {
    activeProject = project;
    $('#empty-projects').addClass('d-none');
    $('#project-dashboard').toggleClass('d-none', currentView !== 'dashboard');
    $('#asset-workspace').toggleClass('d-none', !['requirements', 'test-cases'].includes(currentView));
    $('#execution-workspace').toggleClass('d-none', !['test-plans', 'runs'].includes(currentView));
    $('#defect-workspace').toggleClass('d-none', currentView !== 'defects');
    $('#automation-workspace').toggleClass('d-none', currentView !== 'automation');
    $('#collaboration-workspace').toggleClass('d-none', currentView !== 'collaboration');
    $('#project-title').text(project.name);
    $('#project-key').text(project.projectKey);
    $('#project-description').text(project.description || t("shell.noProjectDescriptionHasBeenAdded", null, 'No project description has been added.'));
    $('#project-status').text(window.I18n.enumLabel(project.status));
    $('#project-hero').toggleClass('is-archived', project.status !== 'ACTIVE');
    $('.project-monogram').text((project.name || 'V').trim().charAt(0).toUpperCase());
    $('#account-name').text(currentUser.username);
    $('#access-summary').text(t("shell.verifiedProjectAccess", null, 'Verified project access'));
  }
  function chooseProject(id) {
    if (!projects.some(function (p) { return String(p.id) === String(id); })) return;
    if (selecting) return;
    selecting = true;
    selectedId = null;
    announce('project', { project: null });
    $('#project-dashboard, #asset-workspace, #execution-workspace, #defect-workspace, #automation-workspace, #collaboration-workspace').addClass('d-none');
    picker.prop('disabled', true);
    feedback('');
    api.get('projects/' + encodeURIComponent(id))
      .done(function (project) {
        selectedId = String(project.id);
        picker.val(selectedId);
        rememberProject(selectedId);
        showProject(project);
        announce('project', { project: project });
      })
      .fail(function (failure) {
        picker.val('');
        feedback(failure.message);
        if ([403, 404].includes(failure.status)) {
          rememberProject(null);
          // A changed membership or deleted project must be revalidated against the list.
          loadProjects();
        }
      })
      .always(function () { selecting = false; picker.prop('disabled', false); });
  }
  function loadProjects() {
    picker.prop('disabled', true);
    api.get('projects')
      .done(function (rows) {
        projects = rows;
        picker.empty();
        if (!rows.length) {
          selectedId = null;
          announce('project', { project: null });
          rememberProject(null);
          picker.append($('<option>').val('').text(t("shell.noProjectsAvailable", null, 'No projects available')));
          $('#project-dashboard, #asset-workspace, #execution-workspace, #defect-workspace, #automation-workspace, #collaboration-workspace').addClass('d-none');
          $('#empty-projects').removeClass('d-none');
          return;
        }
        rows.forEach(function (project) {
          picker.append($('<option>').val(String(project.id)).text(project.projectKey + ' · ' + project.name));
        });
        const stored = savedProject();
        const next = rows.some(function (p) { return String(p.id) === stored; }) ? stored : String(rows[0].id);
        chooseProject(next);
      })
      .fail(function (failure) { feedback(failure.message); })
      .always(function () { if (!selecting) picker.prop('disabled', false); });
  }
  function bootstrap() {
    if (loading) return;
    loading = true;
    shell.addClass('d-none');
    splash.removeClass('d-none');
    clearProjectContext();
    feedback('');
    setView(location.hash.slice(1));
    api.get('auth/me')
      .done(function (user) {
        currentUser = user;
        $('#current-user').text(user.username);
        $('#admin-entry').toggleClass('d-none', user.systemRole !== 'ADMIN');
        showShell();
        loadProjects();
      })
      .fail(function (failure) {
        if (failure.status !== 401) {
          splash.empty().append($('<span>').text(failure.message));
          splash.append($('<button type="button" class="btn btn-outline-primary btn-sm">').text(t("common.retry", null, 'Retry')).on('click', bootstrap));
        }
      })
      .always(function () { loading = false; });
  }

  picker.on('change', function () { chooseProject(this.value); });
  $('a[data-view]').on('click', function () {
    if (location.hash === this.hash) setView(this.hash.slice(1));
  });
  $(window).on('hashchange', function () { setView(location.hash.slice(1)); });
  $('#logout-button').on('click', function () {
    const button = $(this);
    if (button.prop('disabled')) return;
    button.prop('disabled', true);
    api.post('auth/logout')
      .done(function () {
        currentUser = null;
        projects = [];
        selectedId = null;
        rememberProject(null);
        announce('project', { project: null });
        shell.addClass('d-none');
        api.signIn();
      })
      .fail(function (failure) { feedback(failure.message); })
      .always(function () { button.prop('disabled', false); });
  });
  $('#sidebar-toggle').on('click', function () {
    const opened = $('#sidebar').toggleClass('open').hasClass('open');
    $(this).attr('aria-expanded', String(opened));
  });
  function renderWorkflow() {
    $('#workflow').empty();
    [t('common.requirements'), t('common.testCases'), t('common.testPlans'), t('common.runs'),
      t('common.defects'), t('common.automationImports'), t('collab.navigation', null, 'Teams & tasks')].forEach(function (name, index) {
      const card = $('<div class="workflow-card">');
      card.append($('<span class="workflow-number">').text(String(index + 1).padStart(2, '0')));
      card.append($('<strong>').text(name));
      card.append($('<span class="workflow-later">').text(t("shell.availableInTheSidebar", null, 'Available in the sidebar')));
      $('#workflow').append(card);
    });
  }
  document.addEventListener('veriqra:localechange', function () {
    renderWorkflow(); setView(currentView, true);
    if (activeProject) showProject(activeProject);
  });
  // pageshow also runs when navigating Back to a page restored from the back/forward cache.
  $(window).on('pageshow', function (event) {
    if (!currentUser || (event.originalEvent && event.originalEvent.persisted)) {
      window.I18n.init().then(function () { renderWorkflow(); bootstrap(); });
    }
  });
  $(window).on('pagehide', function () {
    // Prevent a back/forward cache snapshot from displaying old project data before revalidation.
    shell.addClass('d-none');
    splash.removeClass('d-none');
    announce('project', { project: null });
  });
})(jQuery, window.VeriqraApi);
