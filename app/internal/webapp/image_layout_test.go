package webapp

import (
	"os/exec"
	"strings"
	"testing"
)

// A single photo or GIF with no caption drops the bubble. Captions, stickers,
// and a payload that already lists several images do not.
func TestBareImageMessage(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed")
	}
	src, err := staticFS.ReadFile("static/app.js")
	if err != nil {
		t.Fatal(err)
	}
	const harness = `
const assert = require('assert');
const src = require('fs').readFileSync(0, 'utf8');
function grabFn(name) {
  const start = src.indexOf('function ' + name + '(');
  if (start < 0) throw new Error('missing ' + name);
  let i = src.indexOf('{', start), depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) return src.slice(start, i + 1); }
  }
  throw new Error('unclosed ' + name);
}
function grabVar(name) {
  const key = 'var ' + name + ' = ';
  const start = src.indexOf(key);
  if (start < 0) throw new Error('missing var ' + name);
  const end = src.indexOf(';', start);
  if (end < 0) throw new Error('unclosed var ' + name);
  return src.slice(start, end + 1);
}
const code = [
  grabVar('IMAGE_EXT'), grabVar('BARE_FILENAME'), grabVar('GENERIC_MEDIA_BODY'),
  grabFn('isImageMessage'), grabFn('imageCaption'), grabFn('isSticker'),
  grabFn('imageAttachmentCount'), grabFn('isBareImage'),
].join('\n');
eval(code);
const photo = (body, extra) => Object.assign({ MessageID: 'm', MediaID: 'media', MimeType: 'image/jpeg', Body: body }, extra || {});
assert.strictEqual(isBareImage(photo('')), true);
assert.strictEqual(isBareImage(photo('[Photo]')), true);
assert.strictEqual(isBareImage(photo('pic.png')), true);
assert.strictEqual(isBareImage(photo('', { MimeType: 'image/gif' })), true);
assert.strictEqual(isBareImage(photo('[Image]', { MimeType: 'image/gif' })), true);
assert.strictEqual(isBareImage(photo('on the trail')), false, 'caption stays in the bubble');
assert.strictEqual(isBareImage(photo('wow', { MimeType: 'image/gif' })), false);
assert.strictEqual(isBareImage(photo('', { MimeType: 'image/webp' })), false, 'webp sticker already has no bubble');
assert.strictEqual(isBareImage(photo('[Sticker]', { MimeType: 'image/png' })), false);
assert.strictEqual(isBareImage(photo('', { attachments: [{}, {}] })), false, 'several images stay in the bubble');
assert.strictEqual(isBareImage({ MessageID: 't', Body: 'hello' }), false);
assert.ok(src.includes('else if (isBareImage(m)) bubble.classList.add("image-only")'));
assert.ok(!src.includes('isBareImage') || src.includes('image-only'), 'bare images use the image-only class');
console.log('ok');
`
	cmd := exec.Command(node, "-e", harness)
	cmd.Stdin = strings.NewReader(string(src))
	out, err := cmd.CombinedOutput()
	if err != nil || !strings.Contains(string(out), "ok") {
		t.Fatalf("node harness failed: %v\n%s", err, out)
	}
}

func TestBareImageCSSKeepsCaptionedPhotos(t *testing.T) {
	cssb, err := staticFS.ReadFile("static/app.css")
	if err != nil {
		t.Fatal(err)
	}
	css := string(cssb)
	for _, want := range []string{
		".bubble.image-only",
		"background: transparent",
		"padding: 0",
		"object-fit: contain",
		"border-radius: inherit",
		"min-height: 10em",
		"min-width: 320px",
	} {
		if !strings.Contains(css, want) {
			t.Errorf("app.css missing %q", want)
		}
	}
	// Captioned desk photos still fill and crop. The bare-image rule is the one that contains.
	if !strings.Contains(css, "html.desk .msg-row .bubble.has-image:not(.sticker) > .media-image img") ||
		!strings.Contains(css, "object-fit: cover") {
		t.Fatal("captioned photo crop rule was removed")
	}
	bare := css[strings.LastIndex(css, "bubble.image-only"):]
	if !strings.Contains(bare, "object-fit: contain") {
		t.Fatal("bare image rule must show the full picture")
	}
	if strings.Contains(bare, "object-fit: cover") {
		t.Fatal("bare image rule must not crop")
	}
}
