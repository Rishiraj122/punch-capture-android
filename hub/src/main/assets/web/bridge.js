// Browser bridge: gives the page the same window.Native API the Android app had,
// but every call goes to the laptop server. The phone only captures photos and shows results.
(function(){
  if (window.Native) return;
  const OFFLINE = "Can't reach the laptop. Check the server is running and this phone is on the same Wi-Fi.";

  // Quick calls are synchronous so the existing screens work unchanged (fast on a local network).
  function rpc(name, args){
    const x = new XMLHttpRequest();
    try{
      x.open("POST", "/api/rpc/" + name, false);
      x.setRequestHeader("Content-Type", "application/json");
      x.send(JSON.stringify({args}));
    }catch(e){ return JSON.stringify({error: OFFLINE}); }
    if (x.status !== 200) return JSON.stringify({error: x.status ? "Laptop server error " + x.status : OFFLINE});
    return x.responseText;
  }
  async function rpcAsync(name, args){
    const r = await fetch("/api/rpc/" + name, {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({args})});
    return r.json();
  }
  const deliver = (fn, obj) => setTimeout(() => window[fn] && window[fn](JSON.stringify(obj)), 0);

  // A short id per phone, so the capture log shows which device took each photo.
  let device = "";
  try{ device = localStorage.getItem("mv-device") || ""; if(!device){ device = "phone-" + Math.random().toString(36).slice(2,6); localStorage.setItem("mv-device", device); } }catch(e){}

  // ---------- camera: the phone's own camera app via a file input (works over plain http) ----------
  const input = document.createElement("input");
  input.type = "file"; input.accept = "image/*"; input.setAttribute("capture", "environment"); input.hidden = true;
  document.addEventListener("DOMContentLoaded", () => document.body.appendChild(input));
  let pending = null, gotFile = false;

  function cancelPending(){
    if (!pending || gotFile) return;
    const p = pending; pending = null;
    deliver("onPhoto", {cancelled:true, variant:p.vid, section:p.sec, checkpoint:p.cid, vehicle:p.veh});
  }
  input.addEventListener("cancel", cancelPending);
  window.addEventListener("focus", () => setTimeout(() => { if (pending && !gotFile && !input.files.length) cancelPending(); }, 1500));

  input.addEventListener("change", () => {
    const f = input.files && input.files[0];
    if (!f || !pending) return;
    gotFile = true;
    const p = pending; pending = null;
    // show the photo right away (review screen) while it uploads
    try{ window.onCaptured && window.onCaptured(URL.createObjectURL(f), {variant:p.vid, section:p.sec, checkpoint:p.cid, vehicle:p.veh}); }catch(e){}
    const fd = new FormData();
    fd.append("variant", p.vid); fd.append("section", p.sec); fd.append("checkpoint", p.cid);
    fd.append("vehicle", p.veh); fd.append("replace", p.replace || ""); fd.append("device", device);
    fd.append("file", f, f.name || "photo.jpg");
    const x = new XMLHttpRequest();
    x.open("POST", "/api/upload");
    x.upload.onprogress = e => { if (e.lengthComputable && window.toast) window.toast(`Sending to laptop… ${Math.round(e.loaded / e.total * 100)}%`); };
    x.onload = () => { let r; try{ r = JSON.parse(x.responseText); }catch(e){ r = {error:"Laptop server error " + x.status}; } deliver("onPhoto", r); input.value = ""; };
    x.onerror = () => { deliver("onPhoto", {error: "Photo not sent. " + OFFLINE}); input.value = ""; };
    x.send(fd);
  });

  const special = {
    ready: () => "null",
    requestStorage(){},
    takePhoto(vid, sec, cid, veh, replace){
      pending = {vid, sec, cid, veh, replace}; gotFile = false; input.value = "";
      input.click();
    },
    requestThumbs(json){
      JSON.parse(json).forEach(path => setTimeout(() => window.onThumb && window.onThumb(path, "/thumb/" + encodeURIComponent(path).replace(/%2F/g, "/")), 0));
    },
    makeZip(from, to, label){
      rpcAsync("startZip", [from, to, label]).then(({job, error}) => {
        if (error) return deliver("onZip", {error});
        const tick = async () => {
          try{
            const st = await rpcAsync("zipStatus", [job]);
            if (st.error) return deliver("onZip", st);
            if (st.done) return deliver("onZip", st.result);
            if (st.progress && st.progress[1] && window.onZipProgress) window.onZipProgress(st.progress[0], st.progress[1]);
            setTimeout(tick, 600);
          }catch(e){ deliver("onZip", {error: OFFLINE}); }
        };
        tick();
      }).catch(() => deliver("onZip", {error: OFFLINE}));
    },
    // "Share" a zip = download it to this phone.
    shareZip(name){
      const a = document.createElement("a");
      a.href = "/zips/" + encodeURIComponent(name); a.download = name;
      document.body.appendChild(a); a.click(); a.remove();
      return JSON.stringify({ok:true});
    },
  };

  window.Native = new Proxy({}, { get: (_, name) => special[name] || ((...args) => rpc(String(name), args)) });
  window.MV_SERVER = true;
})();
