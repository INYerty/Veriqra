(function ($, api) {
  'use strict';
  const projectStorageKey = 'veriqra.selectedProjectId';
  const shell = $('#app-shell');
  const splash = $('#bootstrap-screen');
  const picker = $('#project-select');
  const alert = $('#page-alert');
  let loading = false;
  let selecting = false;
  let currentUser = null;
  let projects = [];
  let selectedId = null;
  let currentView = 'dashboard';

  function announce(name, detail) { document.dispatchEvent(new CustomEvent('veriqra:' + name, { detail: detail })); }
  function setView(view) {
    currentView = ['dashboard', 'requirements', 'test-cases', 'test-plans', 'runs'].includes(view) ? view : 'dashboard';
    $('[data-view]').removeClass('active').removeAttr('aria-current');
    $('[data-view="' + currentView + '"]').addClass('active').attr('aria-current', 'page');
    const labels = { dashboard: ['OVERVIEW', 'Project dashboard', 'Your current quality workspace.'], requirements: ['TEST ASSETS', 'Requirements', 'Manage requirements and their test coverage.'], 'test-cases': ['TEST ASSETS', 'Test cases', 'Maintain current test definitions and steps.'], 'test-plans': ['PLANNING', 'Test plans', 'Define the cases to execute.'], runs: ['EXECUTION', 'Test runs', 'Inspect frozen snapshots and record attempts.'] };
    $('#view-eyebrow').text(labels[currentView][0]);
    $('#view-title').text(labels[currentView][1]);
    $('#view-description').text(labels[currentView][2]);
    $('#project-dashboard').toggleClass('d-none', currentView !== 'dashboard' || !selectedId);
    $('#asset-workspace').toggleClass('d-none', !['requirements', 'test-cases'].includes(currentView) || !selectedId);
    $('#execution-workspace').toggleClass('d-none', !['test-plans', 'runs'].includes(currentView) || !selectedId);
    $('#sidebar').removeClass('open');
    $('#sidebar-toggle').attr('aria-expanded', 'false');
    announce('view', { view: currentView, projectId: selectedId });
  }

  function savedProject() { try { return sessionStorage.getItem(projectStorageKey); } catch (_) { return null; } }
  function rememberProject(id) { try { if (id) sessionStorage.setItem(projectStorageKey, id); else sessionStorage.removeItem(projectStorageKey); } catch (_) { /* Optional UI preference. */ } }
  function feedback(message) { alert.text(message || '').toggleClass('d-none', !message); }
  function showShell() { splash.addClass('d-none'); shell.removeClass('d-none'); }
  function clearProjectContext() {
    announce('project', { project: null });
    projects = [];
    selectedId = null;
    picker.empty().append($('<option>').val('').text('Loading projects…')).prop('disabled', true);
    $('#project-dashboard, #empty-projects, #asset-workspace, #execution-workspace').addClass('d-none');
    $('#project-key, #project-title, #project-description, #project-status, #account-name').text('');
  }
  function showProject(project) {
    $('#empty-projects').addClass('d-none');
    $('#project-dashboard').toggleClass('d-none', currentView !== 'dashboard');
    $('#asset-workspace').toggleClass('d-none', !['requirements', 'test-cases'].includes(currentView));
    $('#execution-workspace').toggleClass('d-none', !['test-plans', 'runs'].includes(currentView));
    $('#project-title').text(project.name);
    $('#project-key').text(project.projectKey);
    $('#project-description').text(project.description || 'No project description has been added.');
    $('#project-status').text(project.status);
    $('#project-hero').toggleClass('is-archived', project.status !== 'ACTIVE');
    $('.project-monogram').text((project.name || 'V').trim().charAt(0).toUpperCase());
    $('#account-name').text(currentUser.username);
    $('#access-summary').text('Verified project access');
  }
  function chooseProject(id) {
    if (!projects.some(function (p) { return String(p.id) === String(id); })) return;
    if (selecting) return;
    selecting = true;
    selectedId = null;
    announce('project', { project: null });
    $('#project-dashboard, #asset-workspace, #execution-workspace').addClass('d-none');
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
          picker.append($('<option>').val('').text('No projects available'));
          $('#project-dashboard, #asset-workspace, #execution-workspace').addClass('d-none');
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
        showShell();
        loadProjects();
      })
      .fail(function (failure) {
        if (failure.status !== 401) {
          splash.empty().append($('<span>').text(failure.message));
          splash.append($('<button type="button" class="btn btn-outline-primary btn-sm">').text('Retry').on('click', bootstrap));
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
  ['Requirements', 'Test cases', 'Test plans', 'Runs', 'Defects', 'Automation & imports'].forEach(function (name, index) {
    const card = $('<div class="workflow-card">');
    card.append($('<span class="workflow-number">').text(String(index + 1).padStart(2, '0')));
    card.append($('<strong>').text(name));
    card.append($('<span class="workflow-later">').text(index < 4 ? 'Available in the sidebar' : 'UI coming later'));
    $('#workflow').append(card);
  });
  // pageshow also runs when navigating Back to a page restored from the back/forward cache.
  $(window).on('pageshow', function (event) {
    if (!currentUser || (event.originalEvent && event.originalEvent.persisted)) bootstrap();
  });
  $(window).on('pagehide', function () {
    // Prevent a back/forward cache snapshot from displaying old project data before revalidation.
    shell.addClass('d-none');
    splash.removeClass('d-none');
    announce('project', { project: null });
  });
})(jQuery, window.VeriqraApi);
