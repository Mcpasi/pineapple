# Pineapple Design System

Dark-first warm-orange record-store aesthetic

- **Status:** draft
- **Design System version:** 0.1.0
- **Schema version:** 1.0.0
- **Source:** existing-project

## Source of truth

Start with [manifest.json](manifest.json), which indexes the [semantic tokens](tokens.json), [foundations](FOUNDATIONS.md), [AI guidelines](AI-GUIDELINES.md), [preferences](preferences.json), [decisions](DECISIONS.md), component and pattern documentation, and the generated [interactive preview](preview/index.html).

The definition is framework-neutral. The HTML is a generated view, not an independent design specification. Update structured files and regenerate the preview. An agent can create a project-specific [preview/source.html](preview/source.html); its CSS uses `var(--ds-color-accent)` and other semantic token variables, with `<!-- opencode-design-system:theme-tokens -->` inside <head>. It is a showcase, not the design specification. The HTML output is regenerated without replacing its source.

## Progressive loading

Read the manifest and AI guidelines first. Load only task-relevant component and pattern files and the token branches they reference. Screen design briefs go in [screens/](screens/).

## Plugin-independent maintenance

Run node design-system/tools/generate-preview.mjs from the project root to regenerate the preview from structured tokens and, if present, the authored source. For older installations with an existing generator, use node design-system/tools/render-authored-preview.mjs after adding preview/source.html. The project's [AGENTS.md](../AGENTS.md) block points any coding agent to the portable system.
