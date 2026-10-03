/* Messages Enhanced: the message box is a contenteditable <div>, because
 * Chrome on Android only hands keyboard images, stickers and GIFs (Gboard's
 * commitContent) to contenteditable elements, not to <textarea>.
 *
 * TMTextbox(el) gives the div the parts of the textarea API app.js uses:
 * .value, .placeholder, .selectionStart / .selectionEnd (kept while
 * blurred, like a textarea), .setSelectionRange() and a .focus() that
 * restores the caret. The content is kept as plain text and <br> line
 * breaks: anything else (pasted or dropped HTML, keyboard <div>s) is
 * flattened after each edit, outside IME composition. Images that still
 * land in the box as <img> (blob:/data:) are handed to onImage as files. */
(function () {
  "use strict";
  var BLOCK = { DIV: 1, P: 1, LI: 1, UL: 1, OL: 1, BLOCKQUOTE: 1, PRE: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1 };

  // Text of a node tree: text as is, <br> = "\n", block elements on their own lines.
  function textOf(root, strip) {
    var out = "", brk = false;
    function walk(node) {
      for (var c = node.firstChild; c; c = c.nextSibling) {
        if (c.nodeType === 3) {
          if (c.data) { if (brk) { out += "\n"; brk = false; } out += c.data; }
        } else if (c.nodeType === 1) {
          if (c.nodeName === "BR") { if (brk) { out += "\n"; brk = false; } out += "\n"; }
          else if (BLOCK[c.nodeName]) {
            if (out !== "" && (brk || out.charAt(out.length - 1) !== "\n")) out += "\n";
            brk = false;
            var before = out.length;
            walk(c);
            // A block ending in <br> (e.g. <div><br></div>): that <br> only gives the empty line its height.
            if (c.lastChild && c.lastChild.nodeName === "BR" && out.length > before && out.charAt(out.length - 1) === "\n") out = out.slice(0, -1);
            brk = true;
          } else if (c.nodeName !== "IMG" && c.nodeName !== "STYLE" && c.nodeName !== "SCRIPT") walk(c);
        }
      }
    }
    walk(root);
    // A trailing <br> in the box only makes a final empty line visible.
    // So does a "\n" ending the last text node (Chrome writes "a\n\n" for
    // Shift+Enter at the end with white-space: pre-wrap).
    var last = root.lastChild;
    if (strip && last && out.charAt(out.length - 1) === "\n" && (last.nodeName === "BR" || (last.nodeType === 3 && /\n$/.test(last.data)))) out = out.slice(0, -1);
    return out;
  }
  function isFlat(el) {
    for (var c = el.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 1 && c.nodeName !== "BR") return false;
      if (c.nodeType === 3 && c.data.indexOf("\n") >= 0) return false;
      if (c.nodeType !== 1 && c.nodeType !== 3) return false;
    }
    return true;
  }

  window.TMTextbox = function (el, opts) {
    opts = opts || {};
    var saved = { start: 0, end: 0 }, composing = false;

    function inside(node) { return node && (node === el || el.contains(node)); }
    // Offset (in .value characters) of a DOM point inside el.
    function offsetOf(node, off) {
      var r = document.createRange();
      r.setStart(el, 0);
      try { r.setEnd(node, off); } catch (e) { return getValue().length; }
      var d = document.createElement("div");
      d.appendChild(r.cloneContents());
      return textOf(d, false).length;
    }
    function liveSel() {
      var s = window.getSelection && window.getSelection();
      if (!s || !s.rangeCount || !inside(s.anchorNode) || !inside(s.focusNode)) return null;
      var r = s.getRangeAt(0);
      var a = offsetOf(r.startContainer, r.startOffset), b = offsetOf(r.endContainer, r.endOffset);
      var n = getValue().length;
      return { start: Math.min(a, n), end: Math.min(b, n) };
    }
    function getValue() { return textOf(el, true); }
    function setValue(v) {
      v = v == null ? "" : String(v).replace(/\r\n?/g, "\n");
      while (el.firstChild) el.removeChild(el.firstChild);
      var parts = v.split("\n");
      parts.forEach(function (p, i) {
        if (i) el.appendChild(document.createElement("br"));
        if (p) el.appendChild(document.createTextNode(p));
      });
      if (v.charAt(v.length - 1) === "\n") el.appendChild(document.createElement("br"));
      var n = v.length;
      saved = { start: Math.min(saved.start, n), end: Math.min(saved.end, n) };
      sync();
    }
    // DOM point for a .value offset (content is flat: text and <br>).
    function pointAt(i) {
      var pos = 0, idx = 0;
      for (var c = el.firstChild; c; c = c.nextSibling, idx++) {
        var len = c.nodeType === 3 ? c.data.length : 1;
        if (c.nodeType === 3 && i <= pos + len) return { node: c, off: i - pos };
        if (c.nodeType !== 3 && i <= pos) return { node: el, off: idx };
        pos += len;
      }
      return { node: el, off: el.childNodes.length };
    }
    function setSel(a, b) {
      if (!isFlat(el)) normalize(true);
      var n = getValue().length;
      a = Math.max(0, Math.min(a | 0, n)); b = Math.max(a, Math.min(b == null ? a : b | 0, n));
      saved = { start: a, end: b };
      if (document.activeElement !== el) return;
      var s = window.getSelection(), r = document.createRange(), p = pointAt(a), q = pointAt(b);
      r.setStart(p.node, p.off); r.setEnd(q.node, q.off);
      s.removeAllRanges(); s.addRange(r);
    }
    function sync() { el.classList.toggle("empty", !el.firstChild || getValue() === ""); }
    // Flatten anything that isn't text or <br>, keeping the caret.
    function normalize(force) {
      if (composing && !force) return;
      var imgs = el.querySelectorAll("img");
      if (imgs.length && opts.onImage) {
        Array.prototype.forEach.call(imgs, function (im) {
          var src = im.getAttribute("src") || "";
          if (/^(blob:|data:image\/)/.test(src)) opts.onImage(src);
        });
      }
      if (isFlat(el)) { sync(); return; }
      var sel = document.activeElement === el ? liveSel() : null;
      setValue(getValue());
      if (sel) setSel(sel.start, sel.end);
    }

    Object.defineProperty(el, "value", { configurable: true, get: getValue, set: setValue });
    Object.defineProperty(el, "placeholder", {
      configurable: true,
      get: function () { return el.getAttribute("data-placeholder") || ""; },
      set: function (v) { el.setAttribute("data-placeholder", v == null ? "" : String(v)); },
    });
    Object.defineProperty(el, "selectionStart", { configurable: true, get: function () { var s = liveSel(); if (s) saved = s; return saved.start; } });
    Object.defineProperty(el, "selectionEnd", { configurable: true, get: function () { var s = liveSel(); if (s) saved = s; return saved.end; } });
    el.setSelectionRange = function (a, b) { setSel(a, b); };
    var nativeFocus = HTMLElement.prototype.focus;
    el.focus = function (o) {
      var was = document.activeElement === el;
      nativeFocus.call(el, o);
      if (!was) setSel(saved.start, saved.end);
    };

    document.addEventListener("selectionchange", function () {
      if (document.activeElement !== el) return;
      var s = liveSel();
      if (s) saved = s;
    });
    el.addEventListener("compositionstart", function () { composing = true; });
    el.addEventListener("compositionend", function () { composing = false; setTimeout(function () { normalize(false); }, 0); });
    // Capture phase: runs before app.js's input listeners read .value.
    el.addEventListener("input", function () { normalize(false); }, true);
    sync();
    return el;
  };
  window.TMTextbox.textOf = textOf;
})();
