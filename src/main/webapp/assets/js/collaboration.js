(function ($, api) {
  'use strict';
  const t = function (key, fallback) { return window.I18n.t(key, null, fallback); };
  let projectId = null;
  let me = null;
  let managers = [];
  let members = [];
  let teams = [];
  let tasks = [];
  let taskPage = 1;
  let taskTotal = 0;
  const taskPageSize = 25;
  let selectedTeam = null;
  let teamMembers = [];
  let selectedTask = null;
  let requestToken = 0;
  let taskSelectionToken = 0;
  let taskListLoading = false;
  let offers = [];

  function path(suffix) { return 'projects/' + encodeURIComponent(projectId) + '/' + suffix; }
  function taskQuery() {
    const params = new URLSearchParams({ page: String(taskPage), pageSize: String(taskPageSize) });
    [['status', '#collab-filter-status'], ['teamId', '#collab-filter-team'],
      ['assigneeId', '#collab-filter-assignee']].forEach(function (entry) {
      const value = $(entry[1]).val();
      if (value) params.set(entry[0], value);
    });
    return path('tasks?' + params.toString());
  }
  function clearTaskSelection() {
    ++taskSelectionToken;
    selectedTask = null;
    $('#collab-task-detail').addClass('d-none');
  }
  function member(id) { return members.find(function (m) { return m.userId === id; }); }
  function name(id) { const value = member(id); return value ? value.displayName + ' (' + value.username + ')' : '#' + id; }
  function active(m) { return m && m.status === 'ACTIVE' && m.userStatus === 'ACTIVE'; }
  function membershipStatus(status) { return t('collab.membership.' + status.toLowerCase(), status); }
  function manager() { return me && active(member(me.id)) && managers.some(function (m) { return m.userId === me.id && m.status === 'ACTIVE'; }); }
  function admin() { return me && me.systemRole === 'ADMIN'; }
  function lead(team) {
    return team && me && active(member(me.id)) && team.leadUserId === me.id &&
      teamMembers.some(function (m) { return m.teamId === team.id && m.userId === me.id && m.status === 'ACTIVE'; });
  }
  function notice(message, error) {
    $('#collab-feedback').text(message || '').toggleClass('d-none', !message)
      .toggleClass('alert-danger', Boolean(error)).toggleClass('alert-success', Boolean(message) && !error);
  }
  function failure(error) { notice(error.message || t('collab.failed', 'The operation failed.'), true); }
  function empty(container, message) { $(container).empty().append($('<p class="text-secondary small">').text(message)); }
  function option(select, id, label) { $(select).append($('<option>').val(String(id)).text(label)); }
  function busy(form, operation, done) {
    const buttons = $(form).find('button');
    buttons.prop('disabled', true);
    operation().done(function (value) {
      notice(t('collab.saved', 'Saved.'), false);
      if (done) done(value);
      refresh();
    }).fail(failure).always(function () { buttons.prop('disabled', false); });
  }
  function positiveCredit(value, allowZero) {
    const text = String(value || '').trim();
    if (text.length > 19 || !/^(?:0|[1-9][0-9]*)$/.test(text) || (!allowZero && text === '0') || BigInt(text) > 9223372036854775807n) {
      notice(t('collab.invalidAmount', 'Enter a valid whole Credit amount.'), true); return null;
    }
    return text;
  }
  function currentMonth() {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit' }).formatToParts(new Date());
    return parts.find(p => p.type === 'year').value + '-' + parts.find(p => p.type === 'month').value;
  }
  function refreshCredit() {
    const token = requestToken;
    api.get('credits/me').done(function (value) {
      if (token !== requestToken) return;
      $('#collab-credit-balance').text(t('collab.balance', 'Current balance') + ': ' + window.I18n.formatCredit(value.balance));
      const box = $('#collab-credit-history').empty();
      if (!value.transactions.length) empty(box, t('collab.noTransactions', 'No transactions yet.'));
      value.transactions.forEach(function (item) {
        box.append($('<div class="py-1 border-bottom">').text(window.I18n.formatDateTime(item.createdAt) + ' · ' +
          t('collab.ledger.' + item.type.toLowerCase(), item.type) + ' · ' + window.I18n.formatCredit(item.amount) +
          (item.counterpartyUserId ? ' · ' + name(item.counterpartyUserId) : '') +
          (item.transferId ? ' · ' + item.transferId : '')));
      });
    }).fail(function (error) { if (token === requestToken) failure(error); });
  }
  function refreshContribution() {
    const token = requestToken;
    const month = $('#collab-contribution-month').val() || currentMonth();
    $('#collab-contribution-month').val(month);
    api.get(path('contributions?month=' + encodeURIComponent(month))).done(function (rows) {
      if (token !== requestToken) return;
      const box = $('#collab-contribution-list').empty();
      if (!rows.length) empty(box, t('collab.noContribution', 'No accepted tasks this month.'));
      rows.forEach(function (row) {
        box.append($('<div class="py-1 border-bottom">').text(row.displayName + ' (' + row.username + ') · ' +
          t('collab.score', 'Score') + ': ' + row.score + ' · ' + t('collab.acceptedTasks', 'Accepted tasks') + ': ' + row.acceptedTaskCount));
      });
    }).fail(function (error) { if (token === requestToken) failure(error); });
  }
  function refreshOffers() {
    const token = requestToken;
    api.get(path('handoffs')).done(function (rows) {
      if (token !== requestToken) return;
      offers = rows; renderOffers();
    }).fail(function (error) { if (token === requestToken) failure(error); });
  }
  function renderOffers() {
    const box = $('#collab-handoffs').empty();
    if (!offers.length) empty(box, t('collab.noHandoffs', 'No handoff offers.'));
    offers.forEach(function (offer) {
      const row = $('<div class="py-2 border-bottom">');
      row.append($('<span>').text('#' + offer.taskId + ' · ' + name(offer.fromUserId) + ' → ' + name(offer.toUserId) +
        ' · ' + window.I18n.formatCredit(offer.creditAmount) + ' · ' +
        t('collab.handoffStatus.' + offer.status.toLowerCase(), offer.status)));
      function decide(action) {
        const buttons = row.find('button').prop('disabled', true);
        api.post(path('handoffs/' + offer.id + '/' + action), {}).done(function () {
          notice(t('collab.saved', 'Saved.')); refresh();
        }).fail(failure).always(function () { buttons.prop('disabled', false); });
      }
      if (offer.status === 'PENDING' && me && offer.toUserId === me.id) {
        $('<button type="button" class="btn btn-outline-primary btn-sm ms-2">').text(t('collab.acceptHandoff', 'Accept handoff'))
          .on('click', function () { decide('accept'); }).appendTo(row);
        $('<button type="button" class="btn btn-outline-secondary btn-sm ms-2">').text(t('collab.declineHandoff', 'Decline'))
          .on('click', function () { decide('decline'); }).appendTo(row);
      }
      if (offer.status === 'PENDING' && me && offer.fromUserId === me.id) {
        $('<button type="button" class="btn btn-outline-secondary btn-sm ms-2">').text(t('collab.cancelHandoff', 'Cancel offer'))
          .on('click', function () { decide('cancel'); }).appendTo(row);
      }
      box.append(row);
    });
  }
  function refresh() {
    if (!projectId) return;
    const token = ++requestToken;
    taskListLoading = true;
    $('#collab-tasks button, #collab-task-prev, #collab-task-next').prop('disabled', true);
    $.when(api.get(path('managers')), api.get(path('members')),
      api.get(path('teams')), api.get(taskQuery()))
      .done(function (newManagers, newMembers, newTeams, newTasks) {
        if (token !== requestToken) return;
        taskListLoading = false;
        managers = newManagers; members = newMembers; teams = newTeams;
        tasks = newTasks.items; taskTotal = newTasks.total;
        render();
        refreshCredit(); refreshContribution(); refreshOffers();
        if (selectedTeam) selectTeam(selectedTeam.id);
        if (selectedTask) selectTask(selectedTask.task.id);
      }).fail(function (error) { if (token === requestToken) { taskListLoading = false; failure(error); } });
  }
  function renderManagers() {
    const box = $('#collab-managers').empty();
    if (!managers.length) empty(box, t('collab.noManagers', 'No project manager has been appointed.'));
    managers.forEach(function (m) {
      const row = $('<div class="d-flex flex-wrap justify-content-between align-items-center gap-2 py-2 border-bottom">');
      row.append($('<span>').text(name(m.userId) + ' · ' + membershipStatus(m.status)));
      if (admin() && m.status === 'ACTIVE') {
        $('<button type="button" class="btn btn-outline-danger btn-sm">').text(t('collab.revoke', 'Revoke'))
          .on('click', function () {
            if (!window.confirm(t('collab.confirmRevoke', 'Revoke this project manager appointment?'))) return;
            api.delete(path('managers/' + m.userId)).done(function () { notice(t('collab.saved', 'Saved.')); refresh(); }).fail(failure);
          }).appendTo(row);
      }
      box.append(row);
    });
    $('#collab-appoint-form').toggleClass('d-none', !admin());
    const picker = $('#collab-appoint-user').empty();
    members.filter(active).forEach(function (m) { option(picker, m.userId, name(m.userId)); });
  }
  function renderMembers() {
    const box = $('#collab-members').empty();
    if (!members.length) empty(box, t('collab.noMembers', 'No project members.'));
    members.forEach(function (m) {
      box.append($('<div class="py-1 border-bottom">').text(name(m.userId) + ' · ' +
        t('collab.' + m.projectRole.toLowerCase(), m.projectRole) + ' · ' + membershipStatus(m.status)));
    });
    $('#collab-invite-form').toggleClass('d-none', !admin() && !manager());
    ['#collab-team-lead', '#collab-new-lead', '#collab-team-member'].forEach(function (selector) {
      const chosen = $(selector).val();
      $(selector).empty();
    members.filter(active).forEach(function (m) { option(selector, m.userId, name(m.userId)); });
      if (chosen) $(selector).val(chosen);
    });
    const recipient = $('#collab-transfer-user').empty();
    members.filter(function (m) { return active(m) && me && m.userId !== me.id; })
      .forEach(function (m) { option(recipient, m.userId, name(m.userId)); });
    const assigneeFilter = $('#collab-filter-assignee'), chosenAssignee = assigneeFilter.val();
    assigneeFilter.empty().append($('<option>').val('').text(t('collab.allAssignees', 'All assignees')));
    members.forEach(function (m) { option(assigneeFilter, m.userId, name(m.userId)); });
    assigneeFilter.val(chosenAssignee || '');
  }
  function renderTeams() {
    const box = $('#collab-teams').empty();
    if (!teams.length) empty(box, t('collab.noTeams', 'No teams yet.'));
    teams.forEach(function (team) {
      const row = $('<button type="button" class="btn btn-outline-secondary text-start me-2 mb-2">');
      row.text(team.name + ' · ' + t('collab.teamLead', 'Team lead') + ': ' + name(team.leadUserId));
      row.on('click', function () { selectTeam(team.id); });
      box.append(row);
    });
    const teamFilter = $('#collab-filter-team'), chosenTeam = teamFilter.val();
    teamFilter.empty().append($('<option>').val('').text(t('collab.allTeams', 'All teams')));
    teams.forEach(function (team) { option(teamFilter, team.id, team.name); });
    teamFilter.val(chosenTeam || '');
    $('#collab-team-form').toggleClass('d-none', !manager());
    if (!selectedTeam || !teams.some(function (team) { return team.id === selectedTeam.id; })) {
      selectedTeam = null; teamMembers = [];
      $('#collab-team-detail').addClass('d-none');
    }
    renderTaskForm();
  }
  function selectTeam(id) {
    const team = teams.find(function (entry) { return entry.id === id; });
    if (!team) return;
    const token = requestToken;
    api.get(path('teams/' + id + '/members')).done(function (rows) {
      if (token !== requestToken) return;
      selectedTeam = team; teamMembers = rows;
      renderTeamDetail(); renderTaskForm();
      if (selectedTask && selectedTask.task.teamId === id) renderTaskDetail();
    }).fail(failure);
  }
  function renderTeamDetail() {
    const team = selectedTeam;
    if (!team) return;
    $('#collab-team-detail').removeClass('d-none');
    $('#collab-team-title').text(team.name + ' · ' + t('collab.teamLead', 'Team lead') + ': ' + name(team.leadUserId));
    const box = $('#collab-team-members').empty();
    const canManage = manager() || lead(team);
    teamMembers.forEach(function (entry) {
      const row = $('<div class="d-flex flex-wrap justify-content-between align-items-center gap-2 py-1 border-bottom">');
      row.append($('<span>').text(name(entry.userId) + ' · ' + membershipStatus(entry.status)));
      if (canManage && entry.status === 'ACTIVE' && entry.userId !== team.leadUserId) {
        $('<button type="button" class="btn btn-outline-danger btn-sm">').text(t('collab.removeFromTeam', 'Remove from team'))
          .on('click', function () {
            if (!window.confirm(t('collab.confirmRemove', 'Remove this member from the team?'))) return;
            api.post(path('teams/' + team.id + '/members'), { userId: entry.userId, status: 'INACTIVE' })
              .done(function () { notice(t('collab.saved', 'Saved.')); refresh(); }).fail(failure);
          }).appendTo(row);
      }
      box.append(row);
    });
    $('#collab-team-member-form').toggleClass('d-none', !canManage);
    $('#collab-change-lead-form').toggleClass('d-none', !manager());
    const picker = $('#collab-team-member').empty();
    members.filter(active).filter(function (m) {
      return !teamMembers.some(function (entry) { return entry.userId === m.userId && entry.status === 'ACTIVE'; });
    }).forEach(function (m) { option(picker, m.userId, name(m.userId)); });
  }
  function renderTasks() {
    const box = $('#collab-tasks').empty();
    if (!tasks.length) empty(box, t('collab.noTasks', 'No visible tasks.'));
    tasks.forEach(function (task) {
      const row = $('<button type="button" class="btn btn-outline-secondary text-start d-block w-100 mb-2">');
      row.text('#' + task.id + ' · ' + task.title + ' · ' +
        t('collab.status.' + task.status.toLowerCase(), task.status) + ' · ' + name(task.assigneeUserId) +
        ' · ' + t('collab.rewardCredit', 'Reward Credit') + ': ' + window.I18n.formatCredit(task.rewardCredit));
      row.on('click', function () { selectTask(task.id); });
      box.append(row);
    });
    if (selectedTask && !tasks.some(function (task) { return task.id === selectedTask.task.id; })) {
      clearTaskSelection();
    }
    const pages = Math.max(1, Math.ceil(taskTotal / taskPageSize));
    $('#collab-task-page-info').text(window.I18n.t('collab.pageSummary',
      { page: taskPage, pages: pages, total: taskTotal }, 'Page {page} of {pages} · Total {total}'));
    $('#collab-task-prev').prop('disabled', taskPage <= 1);
    $('#collab-task-next').prop('disabled', taskPage >= pages);
  }
  function renderTaskForm() {
    const available = teams.filter(function (team) {
      return team.status === 'ACTIVE' && (manager() || team.leadUserId === (me && me.id));
    });
    $('#collab-task-form').toggleClass('d-none', !available.length);
    const picker = $('#collab-task-team');
    const previous = picker.val(); picker.empty();
    available.forEach(function (team) { option(picker, team.id, team.name); });
    if (previous) picker.val(previous);
    updateAssigneePicker();
  }
  function updateAssigneePicker() {
    const teamId = Number($('#collab-task-team').val());
    const picker = $('#collab-task-assignee').empty();
    if (!teamId) return;
    if (selectedTeam && selectedTeam.id === teamId) {
      teamMembers.filter(function (m) { return m.status === 'ACTIVE' && active(member(m.userId)); })
        .forEach(function (m) { option(picker, m.userId, name(m.userId)); });
      return;
    }
    const token = requestToken;
    api.get(path('teams/' + teamId + '/members')).done(function (rows) {
      if (token !== requestToken || Number($('#collab-task-team').val()) !== teamId) return;
      picker.empty();
      rows.filter(function (m) { return m.status === 'ACTIVE' && active(member(m.userId)); })
        .forEach(function (m) { option(picker, m.userId, name(m.userId)); });
    }).fail(failure);
  }
  function selectTask(id) {
    if (taskListLoading || !tasks.some(function (task) { return task.id === id; })) return;
    const token = requestToken;
    const selection = ++taskSelectionToken;
    selectedTask = null;
    $('#collab-task-detail').addClass('d-none');
    api.get(path('tasks/' + id)).done(function (detail) {
      if (token !== requestToken || selection !== taskSelectionToken || taskListLoading ||
          !tasks.some(function (task) { return task.id === id; })) return;
      selectedTask = detail;
      const team = teams.find(function (value) { return value.id === detail.task.teamId; });
      if (team && (!selectedTeam || selectedTeam.id !== team.id)) selectTeam(team.id);
      renderTaskDetail();
    }).fail(failure);
  }
  function renderTaskDetail() {
    if (!selectedTask || !me) return;
    const task = selectedTask.task;
    const team = teams.find(function (value) { return value.id === task.teamId; });
    const reviewer = manager() || lead(team);
    const self = task.assigneeUserId === me.id;
    $('#collab-task-detail').removeClass('d-none');
    $('#collab-task-detail-title').text('#' + task.id + ' · ' + task.title);
    $('#collab-task-summary').empty()
      .append($('<p class="mb-1">').text(t('collab.assignee', 'Assignee') + ': ' + name(task.assigneeUserId)))
      .append($('<p class="mb-1">').text(t('common.status', 'Status') + ': ' + t('collab.status.' + task.status.toLowerCase(), task.status)))
      .append($('<p class="mb-1">').text(t('collab.rewardCredit', 'Reward Credit') + ': ' + window.I18n.formatCredit(task.rewardCredit)))
      .append($('<p class="mb-1">').text(t('collab.contributionOnAcceptance', 'Contribution on acceptance') + ': +1'))
      .append($('<p class="text-secondary">').text(task.description || ''));
    const box = $('#collab-task-actions').empty();
    function action(label, status) {
      $('<button type="button" class="btn btn-outline-primary btn-sm">').text(label).on('click', function () {
        const note = $('#collab-task-note').val().trim();
        if (status === 'IN_PROGRESS' && task.status === 'SUBMITTED' && !note) {
          notice(t('collab.returnNoteRequired', 'A note is required to return submitted work.'), true); return;
        }
        const buttons = box.find('button').prop('disabled', true);
        api.post(path('tasks/' + task.id + '/status'), { status: status, note: note || null, expectedVersion: task.lockVersion })
          .done(function () { notice(t('collab.saved', 'Saved.')); $('#collab-task-note').val(''); refresh(); })
          .fail(failure).always(function () { buttons.prop('disabled', false); });
      }).appendTo(box);
    }
    if (self && task.status === 'OPEN') action(t('collab.start', 'Start work'), 'IN_PROGRESS');
    if (self && task.status === 'IN_PROGRESS') action(t('collab.submit', 'Submit work'), 'SUBMITTED');
    if (reviewer && !self && task.status === 'SUBMITTED') {
      action(t('collab.accept', 'Accept'), 'ACCEPTED');
      action(t('collab.return', 'Return for changes'), 'IN_PROGRESS');
    }
    if (reviewer && ['OPEN', 'IN_PROGRESS'].includes(task.status)) action(t('collab.cancel', 'Cancel task'), 'CANCELLED');
    $('#collab-reward-form').toggleClass('d-none', !reviewer || task.status !== 'OPEN');
    $('#collab-reward-value').val(task.rewardCredit);
    $('#collab-handoff-form').toggleClass('d-none', !self || !['OPEN', 'IN_PROGRESS'].includes(task.status));
    const handoffPicker = $('#collab-handoff-user').empty();
    if (selectedTeam && selectedTeam.id === task.teamId) teamMembers.filter(function (m) {
      return m.status === 'ACTIVE' && m.userId !== task.assigneeUserId && active(member(m.userId));
    }).forEach(function (m) { option(handoffPicker, m.userId, name(m.userId)); });
    $('#collab-reassign-form').toggleClass('d-none', !reviewer || !['OPEN', 'IN_PROGRESS'].includes(task.status));
    const picker = $('#collab-reassign-user').empty();
    if (selectedTeam && selectedTeam.id === task.teamId) {
      teamMembers.filter(function (m) { return m.status === 'ACTIVE' && active(member(m.userId)); })
        .forEach(function (m) { option(picker, m.userId, name(m.userId)); });
      picker.val(String(task.assigneeUserId));
    }
    const history = $('#collab-task-events').empty();
    selectedTask.events.forEach(function (event) {
      history.append($('<div class="py-1 border-bottom">').text(
        window.I18n.formatDateTime(event.createdAt) + ' · ' +
        t('collab.event.' + event.eventType.toLowerCase(), event.eventType) + ' · ' + name(event.actorUserId) +
        (event.note ? ' · ' + event.note : '')));
    });
  }
  function render() { renderMembers(); renderManagers(); renderTeams(); renderTasks(); }

  $('#collab-refresh').on('click', refresh);
  $('#collab-task-prev').on('click', function () {
    if (taskPage <= 1) return;
    --taskPage; clearTaskSelection(); refresh();
  });
  $('#collab-task-next').on('click', function () {
    if (taskPage >= Math.max(1, Math.ceil(taskTotal / taskPageSize))) return;
    ++taskPage; clearTaskSelection(); refresh();
  });
  $('#collab-filter-status, #collab-filter-team, #collab-filter-assignee').on('change', function () {
    taskPage = 1; clearTaskSelection(); refresh();
  });
  $('#collab-task-team').on('change', updateAssigneePicker);
  $('#collab-appoint-form').on('submit', function (event) {
    event.preventDefault();
    busy(this, function () { return api.post(path('managers'), { userId: Number($('#collab-appoint-user').val()) }); });
  });
  $('#collab-invite-form').on('submit', function (event) {
    event.preventDefault(); const form = this;
    busy(form, function () { return api.post(path('members'), {
      username: $('#collab-invite-username').val().trim(), projectRole: $('#collab-invite-role').val()
    }); }, function () { form.reset(); });
  });
  $('#collab-team-form').on('submit', function (event) {
    event.preventDefault(); const form = this;
    busy(form, function () { return api.post(path('teams'), {
      name: $('#collab-team-name').val().trim(), leadUserId: Number($('#collab-team-lead').val())
    }); }, function (team) { form.reset(); selectedTeam = team; });
  });
  $('#collab-team-member-form').on('submit', function (event) {
    event.preventDefault(); if (!selectedTeam) return;
    busy(this, function () { return api.post(path('teams/' + selectedTeam.id + '/members'), {
      userId: Number($('#collab-team-member').val()), status: 'ACTIVE'
    }); });
  });
  $('#collab-change-lead-form').on('submit', function (event) {
    event.preventDefault(); if (!selectedTeam) return;
    busy(this, function () { return api.post(path('teams/' + selectedTeam.id + '/lead'), {
      leadUserId: Number($('#collab-new-lead').val()), expectedVersion: selectedTeam.lockVersion
    }); });
  });
  $('#collab-task-form').on('submit', function (event) {
    event.preventDefault(); const form = this;
    const rewardCredit = positiveCredit($('#collab-task-reward').val(), true);
    if (rewardCredit === null) return;
    busy(form, function () { return api.post(path('tasks'), {
      teamId: Number($('#collab-task-team').val()), title: $('#collab-task-title').val().trim(),
      description: $('#collab-task-description').val().trim() || null,
      assigneeUserId: Number($('#collab-task-assignee').val()), rewardCredit: rewardCredit
    }); }, function (task) { form.reset(); $('#collab-task-reward').val('0'); taskPage = 1; selectedTask = { task: task, events: [] }; });
  });
  $('#collab-reassign-form').on('submit', function (event) {
    event.preventDefault(); if (!selectedTask) return;
    const task = selectedTask.task;
    busy(this, function () { return api.post(path('tasks/' + task.id + '/assignee'), {
      assigneeUserId: Number($('#collab-reassign-user').val()), expectedVersion: task.lockVersion
    }); });
  });
  $('#collab-transfer-form').on('input change', function () { delete this.dataset.operationId; });
  $('#collab-transfer-form').on('submit', function (event) {
    event.preventDefault(); const form = this;
    const amount = positiveCredit($('#collab-transfer-amount').val(), false);
    if (!amount || !$('#collab-transfer-user').val()) return;
    const operationId = form.dataset.operationId || (form.dataset.operationId = crypto.randomUUID());
    busy(form, function () { return api.post(path('credit-transfers'), {
      recipientUserId: Number($('#collab-transfer-user').val()), amount: amount,
      note: $('#collab-transfer-note').val().trim() || null, operationId: operationId
    }); }, function () { form.reset(); delete form.dataset.operationId; });
  });
  $('#collab-contribution-month').on('change', refreshContribution);
  $('#collab-reward-form').on('submit', function (event) {
    event.preventDefault(); if (!selectedTask) return;
    const amount = positiveCredit($('#collab-reward-value').val(), true);
    if (amount === null) return;
    const task = selectedTask.task;
    busy(this, function () { return api.post(path('tasks/' + task.id + '/reward'), {
      rewardCredit: amount, expectedVersion: task.lockVersion
    }); });
  });
  $('#collab-handoff-form').on('input change', function () { delete this.dataset.operationId; });
  $('#collab-handoff-form').on('submit', function (event) {
    event.preventDefault(); if (!selectedTask) return;
    const form = this, amount = positiveCredit($('#collab-handoff-amount').val(), false);
    if (!amount || !$('#collab-handoff-user').val()) return;
    const operationId = form.dataset.operationId || (form.dataset.operationId = crypto.randomUUID());
    busy(form, function () { return api.post(path('handoffs'), {
      taskId: selectedTask.task.id, recipientUserId: Number($('#collab-handoff-user').val()),
      amount: amount, note: $('#collab-handoff-note').val().trim() || null, operationId: operationId
    }); }, function () { form.reset(); delete form.dataset.operationId; });
  });
  document.addEventListener('veriqra:project', function (event) {
    ++requestToken;
    ++taskSelectionToken;
    projectId = event.detail && event.detail.project ? event.detail.project.id : null;
    me = null; managers = []; members = []; teams = []; tasks = [];
    taskPage = 1; taskTotal = 0;
    taskListLoading = false;
    clearTaskSelection();
    $('#collab-filter-status, #collab-filter-team, #collab-filter-assignee').val('');
    selectedTeam = null; teamMembers = []; selectedTask = null;
    notice('');
    if (!projectId) return;
    const token = requestToken;
    api.get('auth/me').done(function (user) {
      if (token !== requestToken) return;
      me = user; refresh();
    }).fail(function (error) { if (token === requestToken) failure(error); });
  });
  document.addEventListener('veriqra:localechange', function () {
    if (!projectId || !me) return;
    notice('');
    render();
    if (selectedTeam) renderTeamDetail();
    if (selectedTask) renderTaskDetail();
    renderOffers(); refreshCredit(); refreshContribution();
  });
})(jQuery, window.VeriqraApi);
