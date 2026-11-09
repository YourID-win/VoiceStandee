/* The settings page: sliders and a live preview. Changes are saved by themselves and reach OBS through the live feed. */
(function () {
  'use strict';

  var DEFAULTS = { h: 360, gap: 24, names: false, align: 'center', idle: 0.62, anim: 'hop',
                   hopHeight: 2.0, hopSpeed: 0.32, squash: 1.0, tilt: 0 };
  var PRESETS = {
    tiny:    { anim: 'hop', hopHeight: 1.6, hopSpeed: 0.26, squash: 1.2, tilt: 0 },
    normal:  { anim: 'hop', hopHeight: 3.5, hopSpeed: 0.55, squash: 1.2, tilt: 0 },
    relaxed: { anim: 'hop', hopHeight: 4.5, hopSpeed: 0.85, squash: 2.0, tilt: 1.5 },
    subtle:  { anim: 'hop', hopHeight: 0.8, hopSpeed: 0.45, squash: 0.4, tilt: 0 }
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
    settings = clone(DEFAULTS);
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
      return real;
    }
    $('previewNote').textContent = 'いまは通話に誰もいないので、見本を表示しています(あなたの立ち絵を使います)。';
    var img = serverState.self && serverState.self.img;
    return ['A', 'B', 'C'].map(function (letter, i) {
      return { id: 'demo' + i, name: 'プレビュー' + (i + 1), img: img || null, avatar: placeholderAvatar(letter), speaking: false };
    });
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

  // ---- frame: the preview is the OBS screen drawn smaller ------------------------------------------------

  function fitFrame() {
    var size = $('canvas').value.split('x');
    var w = parseInt(size[0], 10), h = parseInt(size[1], 10);
    var scale = Math.min(1, holder.clientWidth / w);
    frame.style.width = w + 'px';
    frame.style.height = h + 'px';
    frame.style.transform = 'scale(' + scale + ')';
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
      writeFields();
      engine.apply(settings);
    }
    refresh();
  });
})();
