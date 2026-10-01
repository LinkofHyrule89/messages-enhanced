// AudioWorklet for live typing: forwards mic samples (channel 0, at the
// AudioContext's rate) to the page in ~2048-sample batches.
class TMPCMCapture extends AudioWorkletProcessor {
  constructor() { super(); this.buf = new Float32Array(2048); this.n = 0; }
  process(inputs) {
    var ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (var i = 0; i < ch.length; i++) {
        this.buf[this.n++] = ch[i];
        if (this.n === this.buf.length) { this.port.postMessage(this.buf); this.buf = new Float32Array(2048); this.n = 0; }
      }
    }
    return true;
  }
}
registerProcessor("tm-pcm-capture", TMPCMCapture);
