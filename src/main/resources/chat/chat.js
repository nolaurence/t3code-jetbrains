(() => {
  'use strict';
  const byId = (id) => document.getElementById(id);
  const viewport = byId('viewport');
  const messages = byId('messages');
  const draft = byId('prompt');
  const send = byId('send');
  const stop = byId('stop');
  const error = byId('error');
  const activity = byId('activity');
  const jump = byId('jump');
  const permissions = byId('permissions');
  const menu = byId('permission-menu');
  const composer = byId('composer');
  const modelTrigger = byId('model-trigger');
  const modelPicker = byId('model-picker');
  const modelSearch = byId('model-search');
  const modelList = byId('model-list');
  const modelEfforts = byId('model-efforts');
  const customModel = byId('custom-model-id');
  const modelError = byId('model-error');
  const catalog = {CODEX: [], CLAUDE: []};
  const providerLabel = {CODEX: 'Codex', CLAUDE: 'Claude Code'};
  const effortLabels = {low: 'Low', medium: 'Medium', high: 'High', xhigh: 'Extra High', max: 'Max', minimal: 'Minimal', none: 'None'};
  let selection = {provider: 'CODEX'};
  let activeProvider = 'CODEX';
  let providerLocked = false;
  const markdown = window.markdownit({html: false, linkify: false});
  const escape = markdown.utils.escapeHtml;
  markdown.renderer.rules.image = (tokens, index) => escape(tokens[index].content);
  const copyIcon = '<svg class="icon" aria-hidden="true"><use href="#copy"/></svg>';
  markdown.renderer.rules.fence = (tokens, index) => {
    const token = tokens[index];
    const language = token.info.trim().split(/\s+/, 1)[0] || 'Code';
    return `<div class="code-block"><div class="code-header"><span>${escape(language)}</span><button type="button" data-copy title="Copy code" aria-label="Copy code">${copyIcon}</button></div><pre><code>${escape(token.content)}</code></pre></div>`;
  };

  let ready = false;
  let running = false;
  let submitting = false;
  let stopping = false;
  let requestedMode = 'approval-required';
  let requestedSpeed = 'default';
  let pendingApproval = null;
  const timelineView = window.embeddedChat.mountTimeline(messages, (text) => markdown.render(text));
  const permissionView = window.embeddedChat.mountPermissions(menu);
  const speedView = window.embeddedChat.mountSpeed(byId('speed-options'));
  let activityLabel = '';
  const post = (type, details = {}) => window.postToHost({type, ...details});
  const atEnd = () => viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop <= 36;
  const scrollToEnd = () => { viewport.scrollTop = viewport.scrollHeight; };
  const updateJump = () => { jump.hidden = document.body.hasAttribute('data-draft') || atEnd(); };

  function updateControls() {
    send.disabled = !ready || submitting || !draft.value.trim();
    send.hidden = running;
    stop.hidden = !running;
    stop.disabled = stopping;
    permissions.disabled = running || submitting;
    modelTrigger.disabled = !ready || running || submitting;
  }

  function closePermissions(focus = false) {
    menu.hidden = true;
    permissions.setAttribute('aria-expanded', 'false');
    if (focus) permissions.focus();
  }

  function openModels() {
    closePermissions();
    activeProvider = selection.provider;
    modelSearch.value = '';
    customModel.value = '';
    modelPicker.hidden = false;
    modelTrigger.setAttribute('aria-expanded', 'true');
    renderModels();
    modelSearch.focus();
    if (activeProvider === 'CODEX' || !providerLocked) post('openModels');
  }

  function closeModels(focus = false) {
    if (modelPicker.hidden) return;
    closePermissions();
    modelPicker.hidden = true;
    modelTrigger.setAttribute('aria-expanded', 'false');
    modelError.hidden = true;
    if (focus) modelTrigger.focus();
  }

  function modelButton(label, subtitle, chosen, action) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'model-option';
    button.setAttribute('aria-pressed', String(chosen));
    const caption = document.createElement('span');
    caption.textContent = label;
    button.title = subtitle || label;
    button.append(caption);
    if (chosen) {
      const check = document.createElement('span');
      check.setAttribute('aria-hidden', 'true');
      check.textContent = '\u2713';
      button.append(check);
    }
    button.addEventListener('click', action);
    return button;
  }

  function chooseModel(provider, model, effort) {
    if (running || submitting) return;
    post('selectModel', {provider, model: model ?? null, effort: effort ?? null});
  }

  function modelRows(provider) {
    const items = catalog[provider];
    if (provider === selection.provider && selection.model && !items.some((model) => model.id === selection.model)) {
      return [...items, {id: selection.model, label: selection.model, efforts: []}];
    }
    return items;
  }

  function renderModels() {
    const selected = modelRows(selection.provider).find((model) => (model.id ?? null) === (selection.model ?? null));
    const modelName = selected?.id ? selected.label : `${providerLabel[selection.provider]} default`;
    byId('model-label').textContent = modelName + (selection.effort ? ` ${effortLabels[selection.effort] ?? selection.effort}` : '');
    const logo = selection.provider === 'CODEX' ? 'logo-codex' : 'logo-claude';
    modelTrigger.querySelector('.provider-icon').innerHTML = `<svg aria-hidden="true"><use href="#${logo}"/></svg>`;
    modelTrigger.querySelector('.model-fast')?.remove();
    renderSpeed();
    if (modelPicker.hidden) return;
    document.querySelectorAll('#model-providers button').forEach((button) => {
      button.disabled = providerLocked && button.dataset.provider !== selection.provider;
      button.setAttribute('aria-pressed', String(activeProvider === button.dataset.provider));
      if (button.disabled) button.title = 'Start a new chat to switch providers';
    });
    const query = modelSearch.value.trim().toLowerCase();
    const models = modelRows(activeProvider).filter((model) => `${model.label} ${model.id ?? ''}`.toLowerCase().includes(query));
    modelList.replaceChildren(...models.map((model) => {
      const selectedHere = activeProvider === selection.provider && (model.id ?? null) === (selection.model ?? null);
      return modelButton(model.label, model.id, selectedHere, () => chooseModel(activeProvider, model.id, selectedHere ? selection.effort : null));
    }));
    if (!models.length) {
      const empty = document.createElement('p');
      empty.textContent = query ? 'No matching models' : 'No models listed yet';
      empty.className = 'model-empty';
      modelList.append(empty);
    }
    const activeSelection = activeProvider === selection.provider;
    byId('reasoning-model').textContent = activeSelection ? modelName : '';
    const reasoning = activeSelection ? (selected?.efforts ?? []) : [];
    const efforts = activeSelection ? [null, ...reasoning] : [];
    modelEfforts.replaceChildren(...efforts.map((effort) => modelButton(
      effort === null ? 'Model default' : effortLabels[effort] ?? effort,
      effort === null && selected?.defaultEffort ? effortLabels[selected.defaultEffort] ?? selected.defaultEffort : '',
      effort === (selection.effort ?? null),
      () => { chooseModel(activeProvider, selection.model, effort); closeModels(true); },
    )));
    if (!activeSelection || !reasoning.length) {
      const empty = document.createElement('p');
      empty.className = 'model-empty';
      empty.textContent = activeSelection ? 'Custom levels are not listed for this model.' : 'Choose a model to see its reasoning settings.';
      modelEfforts.append(empty);
    }
  }

  function selectCustomModel() {
    const model = customModel.value.trim();
    if (!model || !customModel.reportValidity()) return;
    chooseModel(activeProvider, model, null);
  }

  function icon(id) {
    return window.embeddedChat.icon(id);
  }

  const modeIcons = {'approval-required': 'lock', 'auto-accept-edits': 'pen', auto: 'shield', 'full-access': 'shield-off', 'read-only': 'lock'};
  function runtimeOptions() {
    if (selection.provider === 'CODEX') return Object.entries(window.embeddedChat.senderRuntimeModes).map(([id, option]) => ({id, ...option, icon: icon(modeIcons[id])}));
    return [{id: 'read-only', label: 'Read only', description: 'Read and search files.', icon: icon('lock')},
      {id: 'auto-accept-edits', label: 'Auto-accept edits', description: 'Also allow editing and writing files.', icon: icon('pen')}];
  }

  function renderPermissions() {
    const options = runtimeOptions();
    if (!options.some((option) => option.id === requestedMode)) requestedMode = options[0].id;
    const option = options.find((option) => option.id === requestedMode);
    byId('permission-label').textContent = option.label;
    permissions.querySelector('use').setAttribute('href', `#${modeIcons[requestedMode]}`);
    permissions.setAttribute('aria-label', `Permissions: ${option.label}`);
    permissionView({options, value: requestedMode, onChange: (id) => {
      requestedMode = id;
      renderPermissions();
      closePermissions(true);
    }});
  }

  function renderSpeed() {
    const model = modelRows(selection.provider).find((m) => (m.id ?? null) === (selection.model ?? null));
    const available = selection.provider === 'CODEX' && model?.fast === true;
    if (!available) requestedSpeed = 'default';
    byId('speed-options').hidden = !available || activeProvider !== 'CODEX';
    if (available) speedView({value: requestedSpeed,
      options: [{id: 'default', label: 'Standard'}, {id: 'fast', label: 'Fast', description: 'Respond faster at a higher rate.', icon: icon('bolt')}],
      onChange: (id) => { requestedSpeed = id; renderModels(); }});
    if (requestedSpeed === 'fast') modelTrigger.querySelector('.provider-icon').insertAdjacentHTML('afterend', '<svg class="icon model-fast" aria-label="Fast"><use href="#bolt"/></svg>');
  }

  function renderApproval(approval) {
    pendingApproval = approval || null;
    byId('approval').hidden = !pendingApproval;
    if (!pendingApproval) return;
    byId('approval-action').textContent = approval.action;
    byId('approval-detail').textContent = approval.detail;
    byId('approval-detail').hidden = !approval.detail;
    byId('accept-approval').disabled = byId('decline-approval').disabled = false;
  }

  function decideApproval(decision) {
    if (!pendingApproval) return;
    byId('accept-approval').disabled = byId('decline-approval').disabled = true;
    post('decideApproval', {id: pendingApproval.id, decision});
  }

  window.applyChatUpdate = (state) => {
    const follow = atEnd();
    if (state.theme) document.documentElement.dataset.theme = state.theme;
    if (state.project !== undefined) {
      byId('project').textContent = state.project;
      byId('hero-project').textContent = state.project;
      draft.value = state.draft || '';
      ready = true;
    }
    if (state.claudeModels) catalog.CLAUDE = state.claudeModels;
    if (state.codexModels) catalog.CODEX = state.codexModels;
    if (state.selection) selection = state.selection;
    if (state.providerLocked !== undefined) providerLocked = state.providerLocked;
    if (providerLocked) activeProvider = selection.provider;
    if (state.selection || state.claudeModels || state.codexModels) renderModels();
    if (state.selection || state.project !== undefined) renderPermissions();
    if (state.approval !== undefined) renderApproval(state.approval);
    if (state.modelError !== undefined) {
      modelError.textContent = state.modelError;
      modelError.hidden = !state.modelError;
    }
    if (state.title !== undefined) byId('title').textContent = state.title;
    if (state.accepted !== undefined) {
      submitting = false;
      if (draft.value === state.accepted) draft.value = '';
      error.hidden = true;
    }
    if (state.error !== undefined) {
      submitting = false;
      error.textContent = state.error;
      error.hidden = false;
    }
    if (state.messages?.length) {
      timelineView.apply(state.messages, true);
      document.body.removeAttribute('data-draft');
    }
    if (state.updates?.length) timelineView.apply(state.updates, false);
    if (state.running !== undefined) {
      running = state.running;
      if (running) closeModels();
      if (!running) stopping = false;
    }
    if (state.activity !== undefined) activityLabel = state.activity;
    const wait = !pendingApproval && activityLabel && activityLabel !== 'Responding' && !messages.querySelector('.work-group[data-state="running"]');
    const label = activity.querySelector('b');
    if (label.textContent !== activityLabel) label.textContent = activityLabel;
    activity.hidden = !wait;
    updateControls();
    if (follow) scrollToEnd();
    updateJump();
  };

  composer.addEventListener('submit', (event) => {
    event.preventDefault();
    if (!ready || running || submitting || !draft.value.trim()) return;
    submitting = true;
    error.hidden = true;
    closePermissions();
    updateControls();
    post('send', {text: draft.value, mode: requestedMode, speed: requestedSpeed});
  });
  draft.addEventListener('input', () => {
    error.hidden = true;
    updateControls();
    post('draft', {text: draft.value});
  });
  draft.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
      event.preventDefault();
      if (!running) composer.requestSubmit();
    }
  });
  stop.addEventListener('click', () => {
    if (!running || stopping) return;
    stopping = true;
    updateControls();
    post('stop');
  });
  modelTrigger.addEventListener('click', () => { if (modelPicker.hidden) openModels(); else closeModels(true); });
  modelSearch.addEventListener('input', renderModels);
  byId('model-providers').addEventListener('click', (event) => {
    const button = event.target.closest('button[data-provider]');
    if (!button || button.disabled) return;
    activeProvider = button.dataset.provider;
    modelSearch.value = '';
    customModel.value = '';
    renderModels();
    if (activeProvider === 'CODEX') post('openModels');
  });
  byId('use-model-id').addEventListener('click', selectCustomModel);
  customModel.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.isComposing) { event.preventDefault(); selectCustomModel(); }
  });
  modelSearch.addEventListener('keydown', (event) => { if (event.key === 'Enter') event.preventDefault(); });
  permissions.addEventListener('click', () => {
    if (!menu.hidden) closePermissions();
    else {
      menu.hidden = false;
      permissions.setAttribute('aria-expanded', 'true');
      menu.querySelector('[aria-checked="true"]')?.focus();
    }
  });
  byId('accept-approval').addEventListener('click', () => decideApproval('accept'));
  byId('decline-approval').addEventListener('click', () => decideApproval('decline'));
  document.addEventListener('pointerdown', (event) => {
    if (!event.target.closest('.permission')) closePermissions();
    if (!event.target.closest('.model-control')) closeModels();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    if (!menu.hidden) closePermissions(true);
    else if (!modelPicker.hidden) closeModels(true);
  });
  viewport.addEventListener('scroll', updateJump, {passive: true});
  jump.addEventListener('click', scrollToEnd);
  viewport.addEventListener('click', (event) => {
    const copy = event.target.closest('[data-copy]');
    if (event.isTrusted && copy) {
      post('copy', {text: copy.closest('.code-block').querySelector('code').textContent});
      copy.title = 'Copied';
    }
    const link = event.target.closest('a');
    if (link) {
      event.preventDefault();
      if (event.isTrusted && /^https?:\/\//i.test(link.getAttribute('href'))) post('link', {url: link.href});
    }
  });
  post('ready');
})();
