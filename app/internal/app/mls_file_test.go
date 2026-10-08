package app

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"math"
	"testing"

	"github.com/maxghenis/openmessage/internal/db"
)

// encryptMLSFileForTest is the sender side of decryptMLSFile.
func encryptMLSFileForTest(key []byte, fileName string, iv, data []byte) (blob, tag []byte) {
	okm := hkdfSHA256(key, mlsFileSalt, []byte(fileName), 64)
	pad := mlsFilePadding(uint32(len(data)))
	pt := append(append([]byte{}, data...), make([]byte, pad)...)
	pt = binary.BigEndian.AppendUint32(pt, uint32(len(data)))
	pt = binary.BigEndian.AppendUint32(pt, pad)
	block, _ := aes.NewCipher(okm[:32])
	counter := make([]byte, 16)
	copy(counter, iv)
	blob = make([]byte, len(pt))
	cipher.NewCTR(block, counter).XORKeyStream(blob, pt)
	mac := hmac.New(sha256.New, okm[32:])
	mac.Write(iv)
	mac.Write(blob)
	return blob, mac.Sum(nil)
}

// The official web client's padding formula, in floating point.
func jsMLSPadding(d uint32) uint32 {
	if d < 2 {
		return 0
	}
	e := math.Floor(math.Log2(float64(d)))
	e = math.Pow(2, e-(math.Floor(math.Log2(e))+1))
	return uint32(math.Floor((float64(d)+(e-1))/e)*e - float64(d))
}

func TestMLSFilePaddingMatchesWebClient(t *testing.T) {
	for d := uint32(0); d < 300000; d++ {
		if got, want := mlsFilePadding(d), jsMLSPadding(d); got != want {
			t.Fatalf("padding(%d) = %d, want %d", d, got, want)
		}
	}
}

func TestDecryptMLSFileRoundTripAndTamper(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	iv := bytes.Repeat([]byte{9}, 12)
	data := append(append([]byte{}, testAvatarPNG...), bytes.Repeat([]byte{0xAB}, 5000)...)
	blob, tag := encryptMLSFileForTest(key, "group_icon", iv, data)
	got, err := decryptMLSFile(key, "group_icon", iv, tag, uint32(len(data)), blob)
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("decrypt: err=%v equal=%v", err, bytes.Equal(got, data))
	}
	if _, err := decryptMLSFile(key, "other_name", iv, tag, uint32(len(data)), blob); err != errMLSFileTag {
		t.Fatalf("wrong file name: %v", err)
	}
	bad := append([]byte{}, blob...)
	bad[10] ^= 1
	if _, err := decryptMLSFile(key, "group_icon", iv, tag, uint32(len(data)), bad); err != errMLSFileTag {
		t.Fatalf("tampered blob: %v", err)
	}
	if _, err := decryptMLSFile(key, "group_icon", iv, tag, uint32(len(data))+1, blob); err != errMLSFileTrailer {
		t.Fatalf("wrong length: %v", err)
	}
}

func TestFetchEncryptedGroupIcon(t *testing.T) {
	const blobURL = "https://rcs-copper-us.googleapis.com/download/test-icon"
	key := bytes.Repeat([]byte{1}, 32)
	iv := bytes.Repeat([]byte{2}, 12)
	icon := append(append([]byte{}, testAvatarPNG...), 0x55, 0x66)
	blob, tag := encryptMLSFileForTest(key, "group_icon", iv, icon)
	mock := &mockGMClient{avatarDownloads: map[string][]byte{blobURL: blob}}
	a := newTestApp(t, mock)
	enc := &db.EncryptedGroupIcon{URL: blobURL, FileName: "group_icon", Key: key, IV: iv, Tag: tag, Length: uint32(len(icon))}
	cand := noURLGroupCandidate("g5", false)
	cand.EncryptedGroupIcon = enc

	a.fetchGoogleGroupAvatar(cand)
	av, _ := a.Store.GetContactAvatar("sms", "conv:g5", "", "")
	if av == nil || !bytes.Equal(av.ImageData, icon) || av.MimeType != "image/png" {
		t.Fatalf("decrypted icon not cached: %+v", av)
	}
	a.fetchGoogleGroupAvatar(cand) // same version: no download
	forced := cand
	forced.Force = true
	a.fetchGoogleGroupAvatar(forced) // Refresh everything: downloaded again
	mock.mu.Lock()
	n := mock.avatarDownloadCalls[blobURL]
	mock.mu.Unlock()
	if n != 2 {
		t.Fatalf("downloads = %d, want 2", n)
	}

	// A new version that fails to decrypt drops the outdated icon.
	badEnc := *enc
	badEnc.Tag = bytes.Repeat([]byte{3}, 32)
	bad := cand
	bad.EncryptedGroupIcon = &badEnc
	a.fetchGoogleGroupAvatar(bad)
	if groupIconHash(t, a, "g5") != "" {
		t.Fatal("undecryptable icon must clear the cached one")
	}

	// A decrypted image identical to a member's photo is refused.
	sum := sha256.Sum256(icon)
	if err := a.Store.UpsertContactAvatar(db.ContactAvatarCandidate{SourcePlatform: "sms", ParticipantID: "p9"}, icon, "image/png", hexSum(sum), 1); err != nil {
		t.Fatal(err)
	}
	c2 := noURLGroupCandidate("g6", true)
	c2.EncryptedGroupIcon = enc
	a.fetchGoogleGroupAvatar(c2)
	if groupIconHash(t, a, "g6") != "" {
		t.Fatal("member photo must never be cached as a group icon")
	}
}

func hexSum(sum [32]byte) string {
	const digits = "0123456789abcdef"
	out := make([]byte, 64)
	for i, b := range sum {
		out[i*2], out[i*2+1] = digits[b>>4], digits[b&15]
	}
	return string(out)
}
