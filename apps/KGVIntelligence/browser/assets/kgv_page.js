// KGV Browser's page agent: turns the page into text with numbered interactive
// elements (the snapshot) and acts on them by number. Injected before every
// command; defines window.__kgv once per document.
(function () {
  if (window.__kgv) return;

  var INTERACTIVE_ROLES = {
    button: 1, link: 1, checkbox: 1, radio: 1, tab: 1, menuitem: 1, option: 1,
    switch: 1, textbox: 1, combobox: 1, searchbox: 1, slider: 1, treeitem: 1
  };
  var BLOCK = /^(ADDRESS|ARTICLE|ASIDE|BLOCKQUOTE|DD|DIV|DL|DT|FIELDSET|FIGCAPTION|FIGURE|FOOTER|FORM|H[1-6]|HEADER|HR|LI|MAIN|NAV|OL|P|PRE|SECTION|TABLE|TR|UL|BR|TD|TH)$/;
  var SKIP = /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE|SVG|CANVAS|IFRAME|OBJECT|EMBED|META|LINK|HEAD)$/;

  var refs = [];

  function visible(el) {
    if (!el.getClientRects().length) return false;
    var s = getComputedStyle(el);
    if (s.visibility === 'hidden' || s.display === 'none' || +s.opacity === 0) return false;
    var r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1;
  }

  function interactive(el) {
    var t = el.tagName;
    if (t === 'A' && el.hasAttribute('href')) return true;
    if (t === 'BUTTON' || t === 'SELECT' || t === 'TEXTAREA' || t === 'SUMMARY') return true;
    if (t === 'INPUT') return el.type !== 'hidden';
    if (el.isContentEditable && (!el.parentElement || !el.parentElement.isContentEditable)) return true;
    var role = el.getAttribute('role');
    if (role && INTERACTIVE_ROLES[role]) return true;
    if (el.hasAttribute('onclick') || el.getAttribute('tabindex') === '0') {
      return getComputedStyle(el).cursor === 'pointer';
    }
    return false;
  }

  function clean(s, n) {
    s = (s || '').replace(/\s+/g, ' ').trim();
    return n && s.length > n ? s.slice(0, n) + '…' : s;
  }

  function label(el) {
    var s = el.getAttribute('aria-label') || '';
    if (!s && el.labels && el.labels.length) s = el.labels[0].innerText;
    if (!s && el.id) {
      var l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
      if (l) s = l.innerText;
    }
    if (!s) s = el.innerText || el.value || el.getAttribute('placeholder') ||
        el.getAttribute('title') || el.getAttribute('alt') || el.getAttribute('name') || '';
    if (!s) {
      var img = el.querySelector && el.querySelector('img[alt]');
      if (img) s = img.getAttribute('alt');
    }
    return clean(s, 80);
  }

  function describe(el, n) {
    var t = el.tagName, kind = el.getAttribute('role') || t.toLowerCase();
    if (t === 'INPUT') kind = (el.type || 'text') + ' field';
    if (t === 'TEXTAREA' || el.isContentEditable) kind = 'text area';
    if (t === 'SELECT') kind = 'dropdown';
    if (t === 'A') kind = 'link';
    var s = '[' + n + ' ' + kind;
    var name = label(el);
    if (name) s += ' "' + name + '"';
    if (t === 'INPUT' || t === 'TEXTAREA') {
      if (el.type === 'checkbox' || el.type === 'radio') s += el.checked ? ' checked' : ' unchecked';
      else if (el.type !== 'password' && el.value) s += ' value="' + clean(el.value, 60) + '"';
      else if (el.placeholder && name !== clean(el.placeholder, 80)) s += ' hint="' + clean(el.placeholder, 40) + '"';
    }
    if (t === 'SELECT' && el.selectedOptions.length) s += ' = "' + clean(el.selectedOptions[0].text, 40) + '"';
    if (el.disabled) s += ' disabled';
    if (t === 'A') {
      var h = el.getAttribute('href') || '';
      if (h && h.charAt(0) !== '#' && h.indexOf('javascript:') !== 0) s += ' → ' + clean(el.href, 70);
    }
    return s + ']';
  }

  function walk(node, out) {
    if (node.nodeType === 3) {
      var t = node.nodeValue.replace(/\s+/g, ' ');
      if (t.trim()) out.push(t);
      return;
    }
    if (node.nodeType !== 1 && node.nodeType !== 11) return;
    var el = node;
    if (node.nodeType === 1) {
      if (SKIP.test(el.tagName) || el.getAttribute('aria-hidden') === 'true') return;
      if (!visible(el) && el.tagName !== 'BODY') return;
      if (interactive(el)) {
        refs.push(el);
        el.setAttribute('data-kgv-ref', refs.length);
        out.push(' ' + describe(el, refs.length) + ' ');
        // Links and buttons are described by their text; don't repeat it.
        if (/^(A|BUTTON|INPUT|SELECT|TEXTAREA)$/.test(el.tagName) || el.getAttribute('role')) return;
      }
      if (BLOCK.test(el.tagName)) out.push('\n');
      if (/^H[1-6]$/.test(el.tagName)) out.push('#'.repeat(+el.tagName[1]) + ' ');
      if (el.tagName === 'LI') out.push('• ');
    }
    var kids = el.shadowRoot ? el.shadowRoot.childNodes : el.childNodes;
    for (var i = 0; i < kids.length; i++) walk(kids[i], out);
    if (node.nodeType === 1 && BLOCK.test(el.tagName)) out.push('\n');
  }

  function snapshot() {
    var old = document.querySelectorAll('[data-kgv-ref]');
    for (var i = 0; i < old.length; i++) old[i].removeAttribute('data-kgv-ref');
    refs = [];
    var out = [];
    if (document.body) walk(document.body, out);
    var text = out.join('').replace(/[ \t]+/g, ' ').replace(/ *\n */g, '\n').replace(/\n{3,}/g, '\n\n').trim();
    return {
      title: document.title, url: location.href, text: text, count: refs.length,
      scroll: Math.round(scrollY),
      height: document.documentElement ? Math.round(document.documentElement.scrollHeight) : 0,
      viewport: innerHeight
    };
  }

  function el(ref) {
    var e = refs[ref - 1];
    if (!e || !e.isConnected) e = document.querySelector('[data-kgv-ref="' + ref + '"]');
    if (!e) throw new Error('No element [' + ref + '] on the page now; take a new snapshot.');
    return e;
  }

  function center(e) {
    e.scrollIntoView({block: 'center', inline: 'center'});
    var r = e.getBoundingClientRect();
    return {x: r.left + r.width / 2, y: r.top + r.height / 2};
  }

  function setValue(e, v) {
    var proto = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(e, v);   // works with React and other frameworks that track the value
    e.dispatchEvent(new Event('input', {bubbles: true}));
    e.dispatchEvent(new Event('change', {bubbles: true}));
  }

  window.__kgv = {
    snapshot: snapshot,
    // Returns the element's centre in CSS pixels for a real tap, after scrolling to it.
    point: function (ref) { return center(el(ref)); },
    click: function (ref) {
      var e = el(ref);
      center(e);
      e.focus && e.focus();
      e.click();
      return 'Clicked ' + describe(e, ref);
    },
    type: function (ref, text, submit) {
      var e = el(ref);
      center(e);
      e.focus();
      if (e.isContentEditable) {
        e.textContent = text;
        e.dispatchEvent(new InputEvent('input', {bubbles: true, data: text}));
      } else if (e.tagName === 'INPUT' || e.tagName === 'TEXTAREA') {
        setValue(e, text);
      } else {
        throw new Error('[' + ref + '] is not a text field.');
      }
      if (submit) {
        var opts = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true};
        e.dispatchEvent(new KeyboardEvent('keydown', opts));
        e.dispatchEvent(new KeyboardEvent('keypress', opts));
        e.dispatchEvent(new KeyboardEvent('keyup', opts));
        if (e.form) {
          if (e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit();
        }
      }
      return 'Typed into ' + describe(e, ref);
    },
    select: function (ref, value) {
      var e = el(ref);
      if (e.tagName !== 'SELECT') throw new Error('[' + ref + '] is not a dropdown.');
      var want = String(value).toLowerCase();
      for (var i = 0; i < e.options.length; i++) {
        var o = e.options[i];
        if (o.value.toLowerCase() === want || clean(o.text).toLowerCase() === want) {
          e.value = o.value;
          e.dispatchEvent(new Event('input', {bubbles: true}));
          e.dispatchEvent(new Event('change', {bubbles: true}));
          return 'Selected "' + clean(o.text) + '"';
        }
      }
      var names = [];
      for (var j = 0; j < e.options.length; j++) names.push(clean(e.options[j].text, 30));
      throw new Error('No option "' + value + '". Options: ' + names.join(', '));
    },
    scroll: function (direction, ref) {
      var target = ref ? el(ref) : null;
      var dy = direction === 'up' ? -0.8 : direction === 'down' ? 0.8 : 0;
      var dx = direction === 'left' ? -0.8 : direction === 'right' ? 0.8 : 0;
      if (target) target.scrollBy(dx * target.clientWidth, dy * target.clientHeight);
      else window.scrollBy(dx * innerWidth, dy * innerHeight);
      return 'Scrolled ' + direction;
    },
    hasText: function (text) {
      return (document.body && document.body.innerText || '').toLowerCase()
          .indexOf(String(text).toLowerCase()) >= 0;
    }
  };
})();
