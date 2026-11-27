'use strict';
// Refresh credentials remain in HttpOnly cookies, never in JS storage or URLs.
window.mangoSessionFetch=async function(url,options={}){
 const auth=/^\/v1\/auth\/(login|refresh|logout|browser-clear)$/.test(url);
 if(!auth)return fetch(url,options);
 const send=()=>fetch(url,{...options,credentials:'same-origin',headers:{...options.headers,'X-Mango-Browser':'1'}});
 // Serialize cookie rotation across messenger and admin tabs.
 return navigator.locks?navigator.locks.request('mango-browser-session',send):send();
};
window.mangoForgetBrowserSession=()=>window.mangoSessionFetch('/v1/auth/browser-clear',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});
