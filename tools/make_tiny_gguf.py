"""
Writes a tiny llama-architecture GGUF with random weights, for testing the
native model layer without downloading a real model.

    python tools/make_tiny_gguf.py <llama.cpp checkout> out.gguf [--template]
"""
import sys, numpy as np
LLAMA = sys.argv[1]
sys.path.insert(0, LLAMA + "/gguf-py")
import gguf
src = gguf.GGUFReader(LLAMA + "/models/ggml-vocab-llama-spm.gguf")
def field(name):
    f = src.fields[name]
    if f.types[0] == gguf.GGUFValueType.ARRAY:
        sub = f.types[1]
        if sub == gguf.GGUFValueType.STRING:
            return [bytes(f.parts[i]).decode("utf-8", "replace") for i in f.data]
        return [f.parts[i][0].item() for i in f.data]
    if f.types[0] == gguf.GGUFValueType.STRING:
        return bytes(f.parts[f.data[0]]).decode()
    return f.parts[f.data[0]][0].item()
tokens = field("tokenizer.ggml.tokens"); scores = field("tokenizer.ggml.scores"); ttype = field("tokenizer.ggml.token_type")
nv, ne, nl, nh, nf = len(tokens), 64, 2, 4, 128
w = gguf.GGUFWriter(sys.argv[2], "llama")
w.add_name("tiny-test")
w.add_context_length(2048); w.add_embedding_length(ne); w.add_block_count(nl); w.add_feed_forward_length(nf)
w.add_head_count(nh); w.add_head_count_kv(nh); w.add_layer_norm_rms_eps(1e-5); w.add_rope_dimension_count(ne // nh)
w.add_tokenizer_model("llama"); w.add_token_list(tokens); w.add_token_scores(scores); w.add_token_types(ttype)
w.add_bos_token_id(field("tokenizer.ggml.bos_token_id")); w.add_eos_token_id(field("tokenizer.ggml.eos_token_id"))
if "--template" in sys.argv:
    w.add_chat_template("{% for m in messages %}<|im_start|>{{ m['role'] }}\n{{ m['content'] }}<|im_end|>\n{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}")
rng = np.random.default_rng(0)
r = lambda *s: (rng.standard_normal(s) * 0.02).astype(np.float32)
w.add_tensor("token_embd.weight", r(nv, ne))
for i in range(nl):
    w.add_tensor(f"blk.{i}.attn_norm.weight", np.ones(ne, np.float32))
    for t in ("attn_q", "attn_k", "attn_v", "attn_output"): w.add_tensor(f"blk.{i}.{t}.weight", r(ne, ne))
    w.add_tensor(f"blk.{i}.ffn_norm.weight", np.ones(ne, np.float32))
    w.add_tensor(f"blk.{i}.ffn_gate.weight", r(nf, ne)); w.add_tensor(f"blk.{i}.ffn_up.weight", r(nf, ne))
    w.add_tensor(f"blk.{i}.ffn_down.weight", r(ne, nf))
w.add_tensor("output_norm.weight", np.ones(ne, np.float32)); w.add_tensor("output.weight", r(nv, ne))
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print("wrote", sys.argv[2], nv, "tokens")
