package app

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"math/bits"
)

// Decryption of Google Messages' end-to-end encrypted (MLS) file blobs, as
// used for MLS group icons ("MlsConversationIcon"). Port of the official web
// client's routine:
//
//	(aesKey, hmacKey) = HKDF-SHA256(ikm=key, salt=mlsFileSalt, info=fileName, 64 bytes)
//	HMAC-SHA256(hmacKey, iv || ciphertext) must equal tag
//	plaintext = AES-256-CTR(aesKey, counter block iv || 00000000)(ciphertext)
//	plaintext = data || zero padding || uint32be(len(data)) || uint32be(len(padding))
//
// with len(data) equal to the length Google sends alongside, and the padding
// length given by mlsFilePadding.

// mlsFileSalt is the HKDF salt: the first 32 bytes of pi's fractional part.
var mlsFileSalt = []byte{
	0x32, 0x43, 0xf6, 0xa8, 0x88, 0x5a, 0x30, 0x8d, 0x31, 0x31, 0x98, 0xa2, 0xe0, 0x37, 0x07, 0x34,
	0x4a, 0x40, 0x93, 0x82, 0x22, 0x99, 0xf3, 0x1d, 0x00, 0x82, 0xef, 0xa9, 0x8e, 0xc4, 0xe6, 0xc8,
}

var (
	errMLSFileParams  = errors.New("mls file: bad parameters")
	errMLSFileTag     = errors.New("mls file: tag mismatch")
	errMLSFileTrailer = errors.New("mls file: bad length trailer or padding")
)

// hkdfSHA256 is RFC 5869 HKDF (extract + expand) with SHA-256.
func hkdfSHA256(ikm, salt, info []byte, n int) []byte {
	ext := hmac.New(sha256.New, salt)
	ext.Write(ikm)
	prk := ext.Sum(nil)
	var out, prev []byte
	for counter := byte(1); len(out) < n; counter++ {
		h := hmac.New(sha256.New, prk)
		h.Write(prev)
		h.Write(info)
		h.Write([]byte{counter})
		prev = h.Sum(nil)
		out = append(out, prev...)
	}
	return out[:n]
}

// mlsFilePadding is the zero padding the sender adds to a file of n bytes
// (the "Padmé" scheme: round up to a multiple of 2^(E - bitlen(E)), E =
// floor(log2 n)).
func mlsFilePadding(n uint32) uint32 {
	if n < 2 {
		return 0
	}
	e := uint32(bits.Len32(n) - 1)
	step := uint64(1) << (e - uint32(bits.Len32(e)))
	padded := (uint64(n) + step - 1) / step * step
	return uint32(padded - uint64(n))
}

// decryptMLSFile authenticates and decrypts an MLS file blob.
func decryptMLSFile(key []byte, fileName string, iv, tag []byte, length uint32, data []byte) ([]byte, error) {
	if len(key) != 32 || len(iv) != 12 || len(tag) != 32 || fileName == "" {
		return nil, errMLSFileParams
	}
	okm := hkdfSHA256(key, mlsFileSalt, []byte(fileName), 64)
	mac := hmac.New(sha256.New, okm[32:])
	mac.Write(iv)
	mac.Write(data)
	if !hmac.Equal(mac.Sum(nil), tag) {
		return nil, errMLSFileTag
	}
	block, err := aes.NewCipher(okm[:32])
	if err != nil {
		return nil, errMLSFileParams
	}
	counter := make([]byte, aes.BlockSize)
	copy(counter, iv)
	pt := make([]byte, len(data))
	cipher.NewCTR(block, counter).XORKeyStream(pt, data)
	pad := mlsFilePadding(length)
	if len(pt) < 8 || uint64(len(pt)) != uint64(length)+uint64(pad)+8 {
		return nil, errMLSFileTrailer
	}
	if binary.BigEndian.Uint32(pt[len(pt)-8:]) != length || binary.BigEndian.Uint32(pt[len(pt)-4:]) != pad {
		return nil, errMLSFileTrailer
	}
	for _, b := range pt[length : length+pad] {
		if b != 0 {
			return nil, errMLSFileTrailer
		}
	}
	return pt[:length], nil
}

func mlsFileErrKind(err error) string {
	switch {
	case errors.Is(err, errMLSFileTag):
		return "tag_mismatch"
	case errors.Is(err, errMLSFileTrailer):
		return "bad_trailer"
	case errors.Is(err, errMLSFileParams):
		return "bad_params"
	}
	return "other"
}
