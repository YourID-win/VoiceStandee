/* The settings page: sliders and a live preview. Changes are saved by themselves and reach OBS through the live feed. */
(function () {
  'use strict';

  var DEFAULTS = { h: 360, gap: 24, names: false, align: 'center', idle: 0.62, anim: 'hop',
                   hopHeight: 2.0, hopSpeed: 0.32, squash: 1.0, tilt: 0, release: 0.15, stopMode: 'smooth',
                   layout: 'row', fit: true, enter: 'fade', leave: 'fade', animMs: 350, orderMode: 'auto', order: [] };
  var PRESETS = {
    tiny:    { anim: 'hop', hopHeight: 1.6, hopSpeed: 0.26, squash: 1.2, tilt: 0, release: 0.10, stopMode: 'smooth' },
    normal:  { anim: 'hop', hopHeight: 3.5, hopSpeed: 0.55, squash: 1.2, tilt: 0, release: 0.15, stopMode: 'smooth' },
    relaxed: { anim: 'hop', hopHeight: 4.5, hopSpeed: 0.85, squash: 2.0, tilt: 1.5, release: 0.30, stopMode: 'smooth' },
    subtle:  { anim: 'hop', hopHeight: 0.8, hopSpeed: 0.45, squash: 0.4, tilt: 0, release: 0.15, stopMode: 'smooth' }
  };

  function $(id) { return document.getElementById(id); }
  function clone(o) { return JSON.parse(JSON.stringify(o)); }

  var base = location.pathname.replace(/\/settings\/?$/, '');
  var frame = $('frame'), stage = $('stage'), holder = $('holder'), statusEl = $('status');
  var engine = StandeeOverlay.create(stage, frame);
  var fields = Array.prototype.slice.call(document.querySelectorAll('[data-key]'));

  var settings = clone(DEFAULTS);
  var loaded = false;
  var serverState = { people: [], self: {} };
  var simTalking = new Set();
  var saveTimer = null;
  var trialHidden = false;

  // ---- fields -------------------------------------------------------------------------------------------

  function readField(el) {
    if (el.type === 'checkbox') return el.checked;
    if (el.type === 'range' || el.type === 'number') return parseFloat(el.value);
    return el.value;
  }

  function showOutput(el) {
    var out = el.parentNode.querySelector('output');
    if (!out) return;
    if (el.type === 'range') out.textContent = el.value + (el.dataset.unit || '');
    else out.textContent = '';
  }

  function writeFields() {
    fields.forEach(function (el) {
      var v = settings[el.dataset.key];
      if (el.type === 'checkbox') el.checked = !!v; else el.value = v;
      showOutput(el);
    });
  }

  fields.forEach(function (el) {
    el.addEventListener('input', function () {
      settings[el.dataset.key] = readField(el);
      showOutput(el);
      changed();
    });
  });

  function changed() {
    engine.apply(settings);
    refresh();
    setStatus('', '');
    clearTimeout(saveTimer);
    saveTimer = setTimeout(save, 350);
  }

  function setStatus(text, kind) {
    statusEl.textContent = text;
    statusEl.className = 'status' + (kind ? ' ' + kind : '');
  }

  function save() {
    setStatus('保存中…', '');
    fetch(base + '/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settings)
    }).then(function (r) {
      if (!r.ok) throw new Error('status ' + r.status);
      setStatus('保存しました(OBSにも反映済み)', 'ok');
    }).catch(function () {
      setStatus('保存できませんでした。ページを開き直してください。', 'bad');
    });
  }

  // ---- presets and reset --------------------------------------------------------------------------------

  Array.prototype.forEach.call(document.querySelectorAll('[data-preset]'), function (b) {
    b.addEventListener('click', function () {
      Object.assign(settings, PRESETS[b.dataset.preset]);
      writeFields();
      changed();
    });
  });
  $('reset').addEventListener('click', function () {
    // everything goes back to the first values, except the order the streamer chose
    var keep = { orderMode: settings.orderMode, order: settings.order };
    settings = Object.assign(clone(DEFAULTS), keep);
    writeFields();
    changed();
  });

  // ---- preview ------------------------------------------------------------------------------------------

  function placeholderAvatar(letter) {
    var svg = "<svg xmlns='http://www.w3.org/2000/svg' width='256' height='256'><rect width='256' height='256' fill='#5865f2'/>"
      + "<text x='128' y='168' font-size='120' text-anchor='middle' fill='white' font-family='sans-serif'>" + letter + "</text></svg>";
    return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
  }

  function previewPeople() {
    var real = serverState.people || [];
    if (real.length > 0) {
      $('previewNote').textContent = 'いま通話にいる人が表示されています。';
      return trialHidden ? real.slice(0, -1) : real;
    }
    $('previewNote').textContent = 'いまは通話に誰もいないので、見本を表示しています(あなたの立ち絵を使います)。';
    var img = serverState.self && serverState.self.img;
    var demo = ['A', 'B', 'C'].map(function (letter, i) {
      return { id: 'demo' + i, name: 'プレビュー' + (i + 1), img: img || null, avatar: placeholderAvatar(letter), speaking: false };
    });
    return trialHidden ? demo.slice(0, -1) : demo;
  }

  function refresh() {
    var simulate = $('sim').checked;
    var people = previewPeople().map(function (p) {
      var copy = Object.assign({}, p);
      if (simulate) copy.speaking = simTalking.has(p.id);
      return copy;
    });
    engine.render({ people: people });
  }

  function writeFieldsAndOrder() {
    writeFields();
    renderOrder();
  }

  setInterval(function () {
    var people = previewPeople();
    if (!$('sim').checked) {
      if (simTalking.size) { simTalking.clear(); refresh(); }
      return;
    }
    if (people.length === 0) return;
    var id = people[Math.floor(Math.random() * people.length)].id;
    if (simTalking.has(id)) simTalking.delete(id); else simTalking.add(id);
    refresh();
  }, 650);
  $('sim').addEventListener('change', refresh);

  // ---- trying the coming and going --------------------------------------------------------------------------

  $('trial').addEventListener('click', function () {
    trialHidden = true;
    refresh();
    setTimeout(function () { trialHidden = false; refresh(); }, Math.max(900, (settings.animMs || 350) + 600));
  });

  // ---- the order: drag the people ---------------------------------------------------------------------------

  // The list is what was saved, then the people in the channel who are not in it yet; names are the latest seen.
  function orderRows() {
    var live = {};
    (serverState.people || []).forEach(function (p) { live[p.id] = p.name; });
    var rows = (settings.order || []).map(function (o) {
      return { id: o.id, name: live[o.id] !== undefined ? live[o.id] : o.name, present: live[o.id] !== undefined };
    });
    var known = {};
    rows.forEach(function (r) { known[r.id] = true; });
    previewPeople().forEach(function (p) {
      if (!known[p.id]) rows.push({ id: p.id, name: p.name, present: true });
    });
    return rows;
  }

  var dragging = null;
  var orderSig = null;
  function renderOrder() {
    var list = $('orderList');
    var rows = orderRows();
    // the live feed arrives often (every time somebody starts or stops speaking): draw the list only when it changed
    var sig = rows.map(function (r) { return r.id + '|' + r.name + '|' + r.present; }).join('|');
    if (sig === orderSig) return;
    orderSig = sig;
    list.textContent = '';
    if (rows.length === 0) {
      var none = document.createElement('li');
      none.className = 'empty';
      none.textContent = '通話に人が入ると、ここに出ます。';
      list.appendChild(none);
      return;
    }
    rows.forEach(function (r) {
      var li = document.createElement('li');
      li.draggable = true;
      li.dataset.id = r.id;
      li.dataset.name = r.name;
      if (!r.present) li.classList.add('absent');
      var grip = document.createElement('span'); grip.className = 'grip'; grip.textContent = '⋮⋮';
      var who = document.createElement('span'); who.className = 'who'; who.textContent = r.name;
      li.appendChild(grip); li.appendChild(who);
      if (!r.present) {
        var x = document.createElement('button');
        x.type = 'button'; x.textContent = '✕'; x.title = '一覧から外す';
        x.addEventListener('click', function () {
          settings.order = (settings.order || []).filter(function (o) { return o.id !== r.id; });
          renderOrder();
          changed();
        });
        li.appendChild(x);
      }
      li.addEventListener('dragstart', function (e) {
        dragging = li;
        li.classList.add('dragging');
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', r.id);
      });
      li.addEventListener('dragend', function () {
        li.classList.remove('dragging');
        Array.prototype.forEach.call(list.children, function (c) { c.classList.remove('over'); });
        dragging = null;
      });
      li.addEventListener('dragover', function (e) {
        if (!dragging || dragging === li) return;
        e.preventDefault();
        li.classList.add('over');
      });
      li.addEventListener('dragleave', function () { li.classList.remove('over'); });
      li.addEventListener('drop', function (e) {
        e.preventDefault();
        li.classList.remove('over');
        if (!dragging || dragging === li) return;
        var before = li.compareDocumentPosition(dragging) & Node.DOCUMENT_POSITION_FOLLOWING; // the dragged one is after this
        list.insertBefore(dragging, before ? li : li.nextSibling);
        saveOrderFromList();
      });
      list.appendChild(li);
    });
  }

  function saveOrderFromList() {
    settings.order = Array.prototype.map.call($('orderList').children, function (li) {
      return { id: li.dataset.id, name: li.dataset.name };
    });
    // the sample people (shown while nobody is in the channel) are kept here, so that the preview follows the drag too;
    // the server keeps only real Discord ids
    settings.orderMode = 'manual';
    writeFields();
    changed();
  }

  // ---- the share address ------------------------------------------------------------------------------------

  function showShare(path) {
    $('shareUrl').value = path ? location.origin + path : '';
    $('shareCopy').disabled = !path;
    $('shareReset').disabled = !path;
    $('shareOff').disabled = !path;
    $('shareIssue').disabled = !!path;
  }
  function shareCall(action) {
    return fetch(base + '/share', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: action })
    }).then(function (r) { if (!r.ok) throw new Error('status ' + r.status); return r.json(); })
      .then(function (j) { showShare(j.path); })
      .catch(function () { setStatus('共有アドレスを変更できませんでした。ページを開き直してください。', 'bad'); });
  }
  $('shareIssue').addEventListener('click', function () { shareCall('issue'); });
  $('shareReset').addEventListener('click', function () {
    if (confirm('共有アドレスを作り直します。いまのアドレスは、使えなくなります。')) shareCall('reset');
  });
  $('shareOff').addEventListener('click', function () {
    if (confirm('共有を止めます。いまのアドレスは、使えなくなります。')) shareCall('off');
  });
  $('shareCopy').addEventListener('click', function () {
    var input = $('shareUrl');
    input.select();
    var done = function () { $('shareCopy').textContent = 'コピーしました'; setTimeout(function () { $('shareCopy').textContent = 'コピー'; }, 1500); };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(input.value).then(done, function () { document.execCommand('copy'); done(); });
    else { document.execCommand('copy'); done(); }
  });
  showShare(null);
  fetch(base + '/share').then(function (r) { return r.json(); }).then(function (j) { showShare(j.path); }).catch(function () { /* the buttons stay as they are */ });

  // ---- frame: the preview is the OBS screen drawn smaller ------------------------------------------------

  function fitFrame() {
    var size = $('canvas').value.split('x');
    var w = parseInt(size[0], 10), h = parseInt(size[1], 10);
    var box = holder.parentNode, cs = getComputedStyle(box);
    var column = box.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    // side by side with the settings (a wide screen), the preview must also fit in the height of the window
    var wide = window.matchMedia('(min-width: 1200px)').matches;
    var room = wide ? Math.max(240, window.innerHeight - 170) : Infinity;
    var scale = Math.min(1, column / w, room / h);
    frame.style.width = w + 'px';
    frame.style.height = h + 'px';
    frame.style.transform = 'scale(' + scale + ')';
    holder.style.width = Math.round(w * scale) + 'px';
    holder.style.height = Math.round(h * scale) + 'px';
  }
  $('canvas').addEventListener('change', fitFrame);
  window.addEventListener('resize', fitFrame);
  $('bg').addEventListener('change', function () { frame.className = 'frame bg-' + $('bg').value; });

  // ---- the address for OBS ------------------------------------------------------------------------------

  $('obsUrl').value = location.origin + base;
  $('copy').addEventListener('click', function () {
    var input = $('obsUrl');
    input.select();
    var done = function () { $('copy').textContent = 'コピーしました'; setTimeout(function () { $('copy').textContent = 'コピー'; }, 1500); };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(input.value).then(done, function () { document.execCommand('copy'); done(); });
    else { document.execCommand('copy'); done(); }
  });

  // ---- start --------------------------------------------------------------------------------------------

  writeFields();
  engine.apply(settings);
  fitFrame();
  StandeeOverlay.connect(base, function (state) {
    serverState = state;
    if (!loaded && state.settings) {
      // take what is saved once; after that this page is the one that changes it
      loaded = true;
      settings = Object.assign(clone(DEFAULTS), state.settings);
      writeFieldsAndOrder();
      engine.apply(settings);
    }
    refresh();
    if (!dragging) renderOrder();
  });
})();
