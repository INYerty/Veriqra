(function ($, api) {
  'use strict';
  // Project and view ownership stay in app.js. This module only renders current test assets.
  let projectId = null;
  let view = 'dashboard';
  let generation = 0;
  let rows = [];
  let detail = null;
  let currentLinks = [];
  let editing = false;
  const root = $('#asset-workspace');
  const kind = function () { return view === 'requirements' ? 'requirements' : 'test-cases'; };
  const base = function (type) { return 'projects/' + encodeURIComponent(projectId) + '/' + type; };
  const valid = function (token) { return token === generation && projectId && view !== 'dashboard'; };
  const label = function (value) { return String(value || '').replaceAll('_', ' '); };

  function notice(message, error) {
    $('#asset-notice').text(message || '').toggleClass('d-none', !message)
      .toggleClass('alert-danger', !!error).toggleClass('alert-success', !!message && !error);
  }
  function formError(message) { $('#asset-form-error').text(message || '').toggleClass('d-none', !message); }
  function reset() {
    generation++;
    rows = [];
    detail = null;
    currentLinks = [];
    editing = false;
    root.find('#asset-list, #asset-detail, #asset-steps, #asset-traces, #step-editor').empty();
    $('#asset-save, #trace-add-button, #trace-submit').prop('disabled', false);
    root.find('#asset-list-panel, #asset-detail-panel, #asset-form, #trace-form').addClass('d-none');
    $('#asset-list-status').text('');
    notice(''); formError('');
  }
  function panels(which) {
    $('#asset-list-panel').toggleClass('d-none', which !== 'list');
    $('#asset-detail-panel').toggleClass('d-none', which !== 'detail');
    $('#asset-form').toggleClass('d-none', which !== 'form');
    $('#trace-form').addClass('d-none');
  }
  function badge(status) {
    const colors = { DRAFT: 'secondary', ACTIVE: 'success', READY: 'success', ARCHIVED: 'secondary', CONFIRMED: 'success', NEEDS_REVIEW: 'warning', REMOVED: 'secondary' };
    return $('<span class="badge text-bg-' + (colors[status] || 'secondary') + '">').text(label(status));
  }
  function field(term, value) {
    return $('<div class="asset-field">').append($('<dt>').text(term), $('<dd>').text(value == null || value === '' ? '—' : String(value)));
  }
  function statusField(status) {
    return $('<div class="asset-field">').append($('<dt>').text('Status'), $('<dd>').append(badge(status)));
  }
  function key(row) { return (view === 'requirements' ? 'REQ-' : 'TC-') + String(row.keyNo).padStart(3, '0'); }

  async function list() {
    if (!projectId || view === 'dashboard') return;
    const token = ++generation;
    detail = null;
    panels('list'); notice('');
    $('#asset-list-title').text(view === 'requirements' ? 'Requirements' : 'Test cases');
    $('#asset-new').text(view === 'requirements' ? 'Create requirement' : 'Create test case');
    $('#asset-list').empty();
    $('#asset-list-status').text('Loading…');
    try {
      const result = await api.get(base(kind()));
      if (!valid(token)) return;
      rows = result;
      $('#asset-list-status').text(result.length ? result.length + ' item(s)' : 'No items yet. Create the first one.');
      result.forEach(function (item) {
        const card = $('<div class="asset-list-row">');
        const summary = $('<div class="asset-list-summary">').append(
          $('<strong>').text(key(item) + ' · ' + item.title),
          $('<p class="mb-1 text-secondary">').text(item.description || 'No description'));
        const meta = $('<div class="asset-list-meta">').append(badge(item.status),
          $('<span>').text(label(item.priority)), $('<small>').text('v' + item.version));
        const open = $('<button type="button" class="btn btn-outline-primary btn-sm">').text('View ' + key(item))
          .on('click', function () { openDetail(item.id); });
        card.append(summary, meta, open);
        $('#asset-list').append(card);
      });
    } catch (failure) { if (valid(token)) { $('#asset-list-status').text(''); notice(failure.message, true); } }
  }

  async function linksForCase(caseId, token) {
    // The approved API has only requirement-side traceability reads.
    const requirements = await api.get(base('requirements'));
    const groups = await Promise.all(requirements.map(function (req) {
      return Promise.resolve(api.get(base('requirements') + '/' + req.id + '/test-cases'))
        .then(function (links) { return links.filter(function (link) { return link.testCase.id === caseId; }).map(function (link) { return { requirement: req, link: link }; }); });
    }));
    if (!valid(token)) return [];
    return groups.flat();
  }

  async function openDetail(id) {
    const token = ++generation;
    detail = null;
    currentLinks = [];
    $('#trace-add-button, #trace-submit').prop('disabled', false);
    editing = false;
    panels('detail'); notice('');
    $('#asset-detail-title').text('Loading…');
    $('#asset-detail, #asset-steps, #asset-traces').empty();
    try {
      const current = await api.get(base(kind()) + '/' + encodeURIComponent(id));
      if (!valid(token)) return;
      const row = view === 'requirements' ? current : current.testCase;
      detail = { row: row, steps: view === 'test-cases' ? current.steps : [] };
      $('#asset-detail-title').text(key(row) + ' · ' + row.title);
      $('#asset-detail').append($('<dl class="asset-fields">').append(
        statusField(row.status), field('Priority', label(row.priority)),
        field('Description', row.description), field('Version', row.version), field('Updated', row.updatedAt)));
      $('#asset-steps-section').toggleClass('d-none', view !== 'test-cases');
      if (view === 'test-cases') {
        $('#asset-detail dl').append(field('Preconditions', row.preconditions));
        renderSteps(current.steps);
      }
      $('#trace-help').text(view === 'requirements' ? 'Linked test cases, including removed history.' : 'Linked requirements, including removed history.');
      const links = view === 'requirements'
        ? await api.get(base('requirements') + '/' + row.id + '/test-cases')
        : await linksForCase(row.id, token);
      if (valid(token) && detail && detail.row.id === id) renderLinks(links);
    } catch (failure) { if (valid(token)) notice(failure.message, true); }
  }
  function renderSteps(steps) {
    const target = $('#asset-steps').empty();
    if (!steps.length) { target.append($('<p class="text-secondary">').text('No steps yet.')); return; }
    steps.forEach(function (step) {
      target.append($('<div class="asset-step">').append(
        $('<strong>').text('Step ' + step.stepOrder),
        $('<div>').append($('<span class="text-secondary">').text('Action: '), document.createTextNode(step.action)),
        $('<div>').append($('<span class="text-secondary">').text('Expected: '), document.createTextNode(step.expectedResult))));
    });
  }
  function renderLinks(links) {
    currentLinks = links;
    const target = $('#asset-traces').empty();
    if (!links.length) { target.append($('<p class="text-secondary">').text('No traceability records yet.')); return; }
    links.forEach(function (entry) {
      const row = view === 'requirements' ? entry.testCase : entry.requirement;
      const status = view === 'requirements' ? entry.status : entry.link.status;
      const requirementId = view === 'requirements' ? detail.row.id : row.id;
      const caseId = view === 'requirements' ? row.id : detail.row.id;
      const item = $('<div class="asset-link-row">').append(
        $('<span>').text((view === 'requirements' ? 'TC-' : 'REQ-') + String(row.keyNo).padStart(3, '0') + ' · ' + row.title), badge(status));
      if (status === 'NEEDS_REVIEW') item.append(actionButton('Confirm', 'confirm', requirementId, caseId, row.title));
      if (status !== 'REMOVED') item.append(actionButton('Mark removed', 'remove', requirementId, caseId, row.title));
      if (status === 'REMOVED') item.append(actionButton('Reattach', '', requirementId, caseId, row.title));
      target.append(item);
    });
  }
  function actionButton(text, action, reqId, caseId, name) {
    return $('<button type="button" class="btn btn-outline-secondary btn-sm">').text(text).on('click', async function () {
      if (action === 'remove' && !window.confirm('Mark the link to "' + name + '" as REMOVED? The history will remain.')) return;
      const button = $(this).prop('disabled', true);
      const token = generation;
      try {
        const path = base('requirements') + '/' + reqId + '/test-cases/' + caseId + (action ? '/' + action : '');
        await api.post(path, {});
        if (valid(token)) await openDetail(detail.row.id);
      } catch (failure) { if (valid(token)) notice(failure.message, true); }
      finally { button.prop('disabled', false); }
    });
  }

  function stepRow(step) {
    const row = $('<div class="step-editor-row">');
    const actionId = 'step-action-' + Math.random().toString(36).slice(2);
    const expectedId = 'step-expected-' + Math.random().toString(36).slice(2);
    row.append($('<strong class="step-number">').text('Step'),
      $('<label class="form-label">').attr('for', actionId).text('Action *'),
      $('<textarea class="form-control step-action" rows="2" required>').attr('id', actionId).val(step.action || ''),
      $('<label class="form-label mt-2">').attr('for', expectedId).text('Expected result *'),
      $('<textarea class="form-control step-expected" rows="2" required>').attr('id', expectedId).val(step.expectedResult || ''));
    const controls = $('<div class="step-actions">');
    [['Move up', -1], ['Move down', 1]].forEach(function (entry) {
      controls.append($('<button type="button" class="btn btn-outline-secondary btn-sm">').text(entry[0]).on('click', function () {
        const sibling = entry[1] < 0 ? row.prev('.step-editor-row') : row.next('.step-editor-row');
        if (sibling.length) { if (entry[1] < 0) row.insertBefore(sibling); else row.insertAfter(sibling); renumberSteps(); }
      }));
    });
    controls.append($('<button type="button" class="btn btn-outline-danger btn-sm">').text('Remove step').on('click', function () {
      if (window.confirm('Remove this step from the test case definition?')) { row.remove(); renumberSteps(); }
    }));
    row.append(controls);
    $('#step-editor').append(row);
    renumberSteps();
  }
  function renumberSteps() {
    $('#step-editor .step-number').each(function (index) { $(this).text('Step ' + (index + 1)); });
  }
  function showForm(update) {
    if (!projectId || view === 'dashboard') return;
    editing = update;
    $('#asset-save').prop('disabled', false);
    formError(''); notice('');
    const row = update ? detail.row : null;
    $('#asset-form')[0].reset();
    $('#asset-form-title').text((update ? 'Edit ' : 'Create ') + (view === 'requirements' ? 'requirement' : 'test case'));
    $('#asset-title').val(row ? row.title : '');
    $('#asset-description').val(row ? row.description || '' : '');
    $('#asset-priority').val(row ? row.priority : 'MEDIUM');
    $('#asset-status-wrap').toggleClass('d-none', !update);
    $('#asset-status').empty();
    (view === 'requirements' ? ['DRAFT', 'ACTIVE', 'ARCHIVED'] : ['DRAFT', 'READY', 'ARCHIVED']).forEach(function (status) {
      $('#asset-status').append($('<option>').val(status).text(label(status)));
    });
    if (row) $('#asset-status').val(row.status);
    $('#asset-case-fields').toggleClass('d-none', view !== 'test-cases');
    $('#asset-preconditions').val(row ? row.preconditions || '' : '');
    $('#step-editor').empty();
    if (view === 'test-cases' && update) detail.steps.forEach(stepRow);
    panels('form');
    $('#asset-title').trigger('focus');
  }

  $(document).on('veriqra:project', function (event) {
    reset();
    projectId = event.originalEvent.detail.project ? String(event.originalEvent.detail.project.id) : null;
    if (projectId && view !== 'dashboard') list();
  });
  $(document).on('veriqra:view', function (event) {
    const next = event.originalEvent.detail.view;
    if (next !== view) { reset(); view = next; }
    if (projectId && view !== 'dashboard') list();
  });
  $('#asset-back').on('click', list);
  $('#asset-new').on('click', function () { showForm(false); });
  $('#asset-edit').on('click', function () { if (detail) showForm(true); });
  $('#asset-form-cancel').on('click', function () { if (editing && detail) openDetail(detail.row.id); else list(); });
  $('#step-add').on('click', function () { stepRow({}); });
  $('#asset-form').on('submit', async function (event) {
    event.preventDefault();
    if (!this.reportValidity()) return;
    const token = generation;
    const button = $('#asset-save').prop('disabled', true);
    formError('');
    const body = { title: $('#asset-title').val().trim(), description: $('#asset-description').val().trim() || null,
      priority: $('#asset-priority').val() };
    if (view === 'test-cases') {
      body.preconditions = $('#asset-preconditions').val().trim() || null;
      body.steps = $('#step-editor .step-editor-row').map(function (index) {
        return { stepOrder: index + 1, action: $(this).find('.step-action').val().trim(),
          expectedResult: $(this).find('.step-expected').val().trim() };
      }).get();
      if (body.steps.some(function (step) { return !step.action || !step.expectedResult; })) {
        formError('Every step needs an action and expected result.'); button.prop('disabled', false); return;
      }
    }
    if (!body.title) { formError('Title is required.'); button.prop('disabled', false); return; }
    if (editing) {
      body.status = $('#asset-status').val();
      body.expectedVersion = detail.row.version;
      if (body.status === 'ARCHIVED' && detail.row.status !== 'ARCHIVED' && !window.confirm('Archive "' + detail.row.title + '"?')) {
        button.prop('disabled', false); return;
      }
    }
    try {
      const saved = editing
        ? await api.put(base(kind()) + '/' + detail.row.id, body)
        : await api.post(base(kind()), body);
      if (valid(token)) await openDetail(saved.id);
    } catch (failure) {
      if (valid(token)) formError(failure.status === 409
        ? 'This item was changed by another operation. Cancel and reload the latest version before saving.'
        : failure.message);
    } finally { if (token === generation) button.prop('disabled', false); }
  });
  $('#trace-add-button').on('click', async function () {
    if (!detail) return;
    const token = generation;
    const button = $(this).prop('disabled', true);
    const target = $('#trace-target').empty();
    try {
      const type = view === 'requirements' ? 'test-cases' : 'requirements';
      const options = await api.get(base(type));
      if (!valid(token)) return;
      const linkedIds = currentLinks.map(function (link) {
        return String(view === 'requirements' ? link.testCase.id : link.requirement.id);
      });
      const eligible = options.filter(function (row) { return row.status !== 'ARCHIVED' && !linkedIds.includes(String(row.id)); });
      eligible.forEach(function (row) {
        target.append($('<option>').val(row.id).text((type === 'test-cases' ? 'TC-' : 'REQ-') +
          String(row.keyNo).padStart(3, '0') + ' · ' + row.title));
      });
      if (!target.children().length) target.append($('<option>').val('').text('No eligible items'));
      $('#trace-target-label').text(view === 'requirements' ? 'Test case' : 'Requirement');
      $('#trace-submit').prop('disabled', !eligible.length);
      $('#trace-form').removeClass('d-none');
      target.trigger('focus');
    } catch (failure) { if (valid(token)) notice(failure.message, true); }
    finally { if (token === generation) button.prop('disabled', false); }
  });
  $('#trace-cancel').on('click', function () { $('#trace-form').addClass('d-none'); });
  $('#trace-form').on('submit', async function (event) {
    event.preventDefault();
    if (!detail || !$('#trace-target').val()) return;
    const token = generation;
    const button = $('#trace-submit').prop('disabled', true);
    const reqId = view === 'requirements' ? detail.row.id : $('#trace-target').val();
    const caseId = view === 'requirements' ? $('#trace-target').val() : detail.row.id;
    try {
      await api.post(base('requirements') + '/' + reqId + '/test-cases/' + caseId, {});
      if (valid(token)) await openDetail(detail.row.id);
    } catch (failure) { if (valid(token)) notice(failure.message, true); }
    finally { if (token === generation) button.prop('disabled', false); }
  });
})(jQuery, window.VeriqraApi);
