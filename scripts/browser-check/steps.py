import json
def db_set(path, val):
    ks=", ".join(f'cljs.core.keyword("{k}")' for k in path)
    return ["eval", f'cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(re_frame.db.app_db, cljs.core.assoc_in, cljs.core.PersistentVector.fromArray([{ks}], true), {val}) && "set {"/".join(path)}"']
def db_get(path):
    ks=", ".join(f'cljs.core.keyword("{k}")' for k in path)
    return ["eval", f'cljs.core.pr_str(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(re_frame.db.app_db), cljs.core.PersistentVector.fromArray([{ks}], true)))']
PARENT=db_set(["ui","parent?"],"true")
CHILD=db_set(["ui","parent?"],"false")
LIST=["eval",'[...document.querySelectorAll(".group")].map(g=>(g.querySelector(".group-name-input")?.value||g.querySelector(".group-name")?.textContent)+": "+[...g.querySelectorAll(".sequence-card")].map(c=>c.querySelector(".sequence-card-title").textContent+(c.classList.contains("is-not-today")?" (dim)":"")).join(", ")).join(" | ")']
WAIT=lambda ms: ["eval", f"new Promise(r=>setTimeout(r,{ms}))"]

# Tap a sequence card by title (the i-th match), then wait a frame or two:
# state changes from an eval re-render asynchronously, so a click straight
# after could hit the old DOM (e.g. a still-disabled button).
CARD=lambda t,i=0: ["eval", f'(()=>{{[...document.querySelectorAll(".sequence-card")].filter(c=>c.querySelector(".sequence-card-title").textContent==={json.dumps(t)})[{i}].click(); return new Promise(r=>setTimeout(()=>r("tapped {t}"),300))}})()']
