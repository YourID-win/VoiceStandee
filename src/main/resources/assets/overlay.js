/* Draws the people and their movement. Used by the OBS page (it boots itself) and by the preview in the settings page. */
(function (global) {
  'use strict';

  var ALIGN = { left: 'flex-start', center: 'center', right: 'flex-end' };

  /**
   * @param stage       the element the people are put into
   * @param varsTarget  the element whose CSS variables carry the settings (the page for OBS, the preview frame for settings)
   */
  function create(stage, varsTarget) {
    var nodes = new Map();
    var anim = 'hop';
    var releaseMs = 150;       // how long a person keeps looking "speaking" after the voice stops
    var stopNow = false;       // stop moving at once instead of finishing the hop that is under way
    var layout = 'row';        // row, column or grid (two lines)
    var fitOn = true;          // shrink everybody so that they all fit on the screen
    var manual = false;        // use the order the streamer chose
    var order = [];            // ids in that order
    var leaveMs = 410;         // how long a leaving person is kept (the leave animation)
    var gapPx = 24;
    var hopPercent = 2;
    var layoutQueued = false;

    function setTalking(el, on) {
      var fig = el.firstChild;
      el.classList.toggle('talk', on);
      fig.dataset.want = on ? '1' : '0';
      if (on && anim !== 'none') fig.classList.add('a-' + anim);
      if (!on && stopNow) fig.classList.remove('a-hop', 'a-pulse');
    }

    // Speaking turns on at once; it turns off only after the release time, which also hides tiny gaps in the voice.
    function followSpeaking(el, speaking) {
      if (speaking) {
        clearTimeout(el._off);
        el._off = null;
        setTalking(el, true);
      } else if (el.classList.contains('talk')) {
        if (releaseMs <= 0) {
          setTalking(el, false);
        } else if (!el._off) {
          el._off = setTimeout(function () { el._off = null; setTalking(el, false); }, releaseMs);
        }
      }
    }

    function apply(s) {
      var st = varsTarget.style;
      st.setProperty('--h', s.h + 'px');
      st.setProperty('--gap', s.gap + 'px');
      st.setProperty('--idle', s.idle);
      st.setProperty('--align', ALIGN[s.align] || 'center');
      st.setProperty('--hop', s.hopHeight / 100);
      st.setProperty('--speed', s.hopSpeed + 's');
      st.setProperty('--squash', s.squash / 100);
      st.setProperty('--tilt', s.tilt + 'deg');
      stage.classList.toggle('names', !!s.names);
      layout = s.layout || 'row';
      fitOn = s.fit !== false;
      manual = s.orderMode === 'manual';
      order = (s.order || []).map(function (o) { return o.id; });
      gapPx = s.gap;
      hopPercent = s.hopHeight || 0;
      stage.dataset.layout = layout;
      stage.dataset.enter = s.enter || 'fade';
      stage.dataset.leave = s.leave || 'fade';
      var ms = s.animMs || 350;
      st.setProperty('--anim', ms + 'ms');
      leaveMs = (s.leave === 'none') ? 0 : ms + 60;
      relayout();
      releaseMs = Math.max(0, (s.release === undefined ? 0.15 : s.release) * 1000);
      stopNow = s.stopMode === 'now';
      if (s.anim !== anim) {
        // only a change of kind restarts the movement; the numbers above take effect while it keeps running
        anim = s.anim;
        nodes.forEach(function (el) {
          var fig = el.firstChild;
          fig.classList.remove('a-hop', 'a-pulse');
          if (el.classList.contains('talk') && anim !== 'none') fig.classList.add('a-' + anim);
        });
      }
    }

    function make(p) {
      var el = document.createElement('div');
      el.className = 'p';
      el.dataset.id = p.id;
      var fig = document.createElement('div');
      fig.className = 'fig';
      var img = document.createElement('img');
      img.alt = '';
      img.decoding = 'async';
      fig.appendChild(img);
      var name = document.createElement('div');
      name.className = 'name';
      el.appendChild(fig);
      el.appendChild(name);
      stage.appendChild(el);
      requestAnimationFrame(function () { requestAnimationFrame(function () { el.classList.add('in'); }); });
      return el;
    }

    function update(el, p) {
      var img = el.querySelector('img');
      var src = p.img || p.avatar || '';
      if (img.dataset.src !== src) { img.dataset.src = src; img.src = src; }
      img.classList.toggle('avatar', !p.img);
      el.querySelector('.name').textContent = p.name;
      followSpeaking(el, !!p.speaking);
    }

    // The movement stops only at the end of a cycle, so that it settles instead of snapping back.
    stage.addEventListener('animationiteration', function (e) {
      var fig = e.target;
      if (fig.dataset && fig.dataset.want === '0') fig.classList.remove('a-hop', 'a-pulse');
    });

    // The people in the order the streamer chose; those who are not in it come after, in the order of the channel.
    function arranged(people) {
      if (!manual || order.length === 0) return people;
      var rank = {};
      order.forEach(function (id, i) { rank[id] = i; });
      return people.map(function (p, i) { return { p: p, k: rank[p.id] === undefined ? order.length + i : rank[p.id] }; })
        .sort(function (a, b) { return a.k - b.k; })
        .map(function (x) { return x.p; });
    }

    // Shrinks everybody (and the gaps) by one factor so that all of them fit on the screen in the chosen layout.
    function relayout() {
      if (layoutQueued) return;
      layoutQueued = true;
      requestAnimationFrame(function () {
        layoutQueued = false;
        var items = [];
        nodes.forEach(function (el) { if (!el.classList.contains('out')) items.push(el); });
        var n = items.length;
        var cols = layout === 'grid' ? Math.max(1, Math.ceil(n / 2)) : n;
        stage.style.setProperty('--cols', Math.max(1, cols));
        stage.style.setProperty('--fit', 1);
        if (!fitOn || n === 0) return;
        var cs = getComputedStyle(stage);
        var availW = stage.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
        var h0 = items.reduce(function (m, el) { return Math.max(m, el.offsetHeight); }, 0);
        var availH = stage.clientHeight - parseFloat(cs.paddingBottom) - parseFloat(cs.paddingTop) - h0 * hopPercent / 100;
        var w = items.map(function (el) { return el.offsetWidth; });
        var h = items.map(function (el) { return el.offsetHeight; });
        var needW, needH;
        if (layout === 'column') {
          needW = Math.max.apply(null, w);
          needH = h.reduce(function (a, b) { return a + b; }, 0) + gapPx * (n - 1);
        } else if (layout === 'grid') {
          var rows = n > cols ? 2 : 1;
          var colW = [], rowH = [];
          items.forEach(function (el, i) {
            var c = i % cols, r = Math.floor(i / cols);
            colW[c] = Math.max(colW[c] || 0, w[i]);
            rowH[r] = Math.max(rowH[r] || 0, h[i]);
          });
          needW = colW.reduce(function (a, b) { return a + b; }, 0) + gapPx * (cols - 1);
          needH = rowH.reduce(function (a, b) { return a + b; }, 0) + gapPx * (rows - 1);
        } else {
          needW = w.reduce(function (a, b) { return a + b; }, 0) + gapPx * (n - 1);
          needH = h0;
        }
        var fit = Math.min(1, availW / Math.max(1, needW), availH / Math.max(1, needH));
        stage.style.setProperty('--fit', Math.max(0.1, fit).toFixed(4));
      });
    }

    // a picture that finishes loading changes the size of its person
    stage.addEventListener('load', relayout, true);
    window.addEventListener('resize', relayout);

    function render(state) {
      var people = arranged(state.people || []);
      var seen = new Set();
      people.forEach(function (p) {
        seen.add(p.id);
        var el = nodes.get(p.id);
        if (!el) { el = make(p); nodes.set(p.id, el); }
        el.classList.remove('out');
        update(el, p);
      });
      relayout();
      var prev = null;
      people.forEach(function (p) {
        var el = nodes.get(p.id);
        var expected = prev ? prev.nextElementSibling : stage.firstElementChild;
        if (el !== expected) stage.insertBefore(el, expected);
        prev = el;
      });
      nodes.forEach(function (el, id) {
        if (seen.has(id) || el.classList.contains('out')) return;
        el.classList.add('out');
        var gone = function () {
          if (el.classList.contains('out')) {
            clearTimeout(el._off);
            el.remove();
            if (nodes.get(id) === el) nodes.delete(id);
            relayout();
          }
        };
        if (leaveMs <= 0) gone(); else setTimeout(gone, leaveMs);
      });
    }

    return { apply: apply, render: render };
  }

  /** Listens to the live feed of one streamer; basePath is the overlay address without a trailing part, e.g. /o/<secret>. */
  function connect(basePath, onState) {
    var es = new EventSource(basePath + '/events');
    es.onmessage = function (ev) { try { onState(JSON.parse(ev.data)); } catch (err) { /* ignore a broken message */ } };
    es.onerror = function () {
      // the browser retries by itself; only a refused connection (closed for good) needs a fresh start
      if (es.readyState === 2) { es.close(); setTimeout(function () { connect(basePath, onState); }, 5000); }
    };
  }

  global.StandeeOverlay = { create: create, connect: connect };

  if (document.body.classList.contains('overlay')) {
    var overlay = create(document.getElementById('stage'), document.documentElement);
    connect(location.pathname.replace(/\/+$/, ''), function (state) {
      if (state.settings) overlay.apply(state.settings);
      overlay.render(state);
    });
  }
})(window);
