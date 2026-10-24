// Portable compiler for a project-authored Design System preview.
// The source is a design demonstration; tokens.json and Markdown remain authoritative.
export const PREVIEW_THEME_MARKER = "<!-- opencode-design-system:theme-tokens -->"

const REVIEW_BRIDGE = `<script id="ds-review-bridge">
(()=>{let port,active=false;const originals=new WeakMap();
function select(element){const type=element.dataset.reviewSelect;if(!port||!type)return;const reference=type==='token'?{type,path:element.dataset.reviewPath,theme:element.dataset.reviewTheme||document.documentElement.dataset.theme}:{type,name:element.dataset.reviewName,file:element.dataset.reviewFile};port.postMessage({type:'selection',reference})}
function mode(enabled){active=enabled;document.documentElement.dataset.reviewSelectMode=String(enabled);for(const element of document.querySelectorAll('[data-review-select]')){if(enabled){if(!originals.has(element))originals.set(element,[element.getAttribute('tabindex'),element.getAttribute('role'),element.getAttribute('aria-label')]);element.setAttribute('tabindex','0');element.setAttribute('role','button');element.setAttribute('aria-label','Seleccionar '+(element.dataset.reviewName||element.dataset.reviewPath||'elemento'))}else{const old=originals.get(element);if(!old)continue;for(const [key,value] of [['tabindex',old[0]],['role',old[1]],['aria-label',old[2]]]){if(value===null)element.removeAttribute(key);else element.setAttribute(key,value)}originals.delete(element)}}}
window.addEventListener('message',event=>{if(event.source!==window.parent||event.data?.type!=='design-system-review-connect'||!event.ports?.[0])return;port?.close();port=event.ports[0];port.onmessage=message=>{if(message.data?.type==='selection-mode')mode(message.data.enabled===true)};port.start()});
document.addEventListener('click',event=>{if(!active)return;event.preventDefault();event.stopImmediatePropagation();const target=event.target instanceof Element?event.target.closest('[data-review-select]'):null;if(target)select(target)},true);
document.addEventListener('keydown',event=>{if(!active||!['Enter',' '].includes(event.key))return;const target=event.target instanceof Element?event.target.closest('[data-review-select]'):null;if(!target)return;event.preventDefault();event.stopImmediatePropagation();select(target)},true);
})();
</script>`

export function renderAuthoredPreview(source, tokens) {
  if (typeof source !== "string" || source.length > 400_000 || !/<html\b/i.test(source) || !/<head\b/i.test(source) || !/<\/head\s*>/i.test(source) || !/<body\b/i.test(source) || !/<\/body\s*>/i.test(source)) {
    throw new Error("Preview source must be a complete HTML document under 400 KB.")
  }
  if (source.split(PREVIEW_THEME_MARKER).length !== 2 || source.indexOf(PREVIEW_THEME_MARKER) > source.search(/<\/head\s*>/i)) {
    throw new Error(`Place ${PREVIEW_THEME_MARKER} exactly once inside <head> in preview/source.html.`)
  }
  const themes = tokens?.themes
  if (!themes || typeof themes !== "object" || Array.isArray(themes) || !Object.keys(themes).length) {
    throw new Error("tokens.json needs at least one theme to compile an authored preview.")
  }
  const css = Object.entries(themes).map(([name, theme], index) => {
    if (!/^[a-zA-Z][a-zA-Z0-9_-]*$/.test(name) || !theme || typeof theme !== "object" || Array.isArray(theme)) {
      throw new Error(`Invalid preview theme: ${name}`)
    }
    const declarations = flatten(theme).map(([key, value]) => {
      if (!/^[a-zA-Z0-9_-]+$/.test(key) || !/^[^;{}<>\\\r\n]+$/.test(value)) throw new Error(`Invalid preview token value: ${key}`)
      return `--ds-${key}:${value};`
    }).join("")
    return `${index === 0 ? ":root," : ""}:root[data-theme="${name}"]{${declarations}}`
  }).join("\n")
  return source.replace(PREVIEW_THEME_MARKER, `<style id="ds-preview-tokens">${css}</style>`).replace(/<\/body\s*>/i, `${REVIEW_BRIDGE}</body>`)
}

function flatten(value, prefix = "") {
  return Object.entries(value).flatMap(([key, entry]) => {
    const name = prefix ? `${prefix}-${key}` : key
    if (entry && typeof entry === "object" && !Array.isArray(entry)) return flatten(entry, name)
    if (typeof entry !== "string" && typeof entry !== "number") throw new Error(`Unsupported preview token: ${name}`)
    return [[name, String(entry)]]
  })
}
