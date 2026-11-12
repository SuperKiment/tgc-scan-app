# Syntaxe Markdown étendue, imports et exports (MPE)

Sommaire :
1. Réglages actifs sur ce poste
2. Syntaxe étendue
3. Imports de fichiers
4. Navigation et présentation
5. Exports

## 1. Réglages actifs sur ce poste

Valeurs par défaut de MPE 0.8.40, aucun réglage utilisateur :
- Maths : KaTeX. Délimiteurs inline `$...$` ou `\(...\)`, bloc `$$...$$` ou `\[...\]`.
- `breakOnSingleNewLine` : `true`.
- `enableWikiLinkSyntax` : `true`, résolution relative, extension `.md`.
- `enableTagSyntax` : `true`.
- `enableExtendedTableSyntax` : `false`, donc pas de cellules fusionnées.
- `enableCriticMarkupSyntax` : `false`.
- `enableScriptExecution` : `false`, donc pas de code chunks.
- Thème preview : `github-light.css`. Thème mermaid : `default`.

## 2. Syntaxe étendue

### GFM
Tableaux, task lists `- [ ]` / `- [x]`, barré `~~texte~~`, autolinks.

### Titres
`## Titre {#mon-id .ma-classe}` : ancre stable, à citer avec `[voir](#mon-id)`.

### Blocs de code
- `{.line-numbers}` : numéros de ligne.
- `{highlight=10}`, `{highlight=10-20}`, `{highlight=[1-3,8,12-14]}` : lignes surlignées. Utile pour montrer la ligne fautive d'un extrait.

````md
```sql {.line-numbers highlight=3}
SELECT TaxId, Label
FROM Taxes.Tax_new
WHERE EstablishmentId = @EstablishmentId -- filtre manquant avant
```
````

### Alertes (style GitHub)

```md
> [!NOTE]
> Contexte utile.

> [!TIP]
> Raccourci.

> [!IMPORTANT]
> À ne pas rater.

> [!WARNING]
> Piège connu.

> [!CAUTION]
> Action risquée.
```

Ces alertes rendent aussi sur GitHub. Préfère-les aux admonitions quand la doc peut être lue ailleurs.

### Admonitions (style MkDocs)

```md
!!! note "Titre"
    Contenu indenté de 4 espaces.

!!! warning "Proc encore appelée"
    `ps_GetTaxList` reste référencée par le tunnel.
```

Types courants : `note`, `abstract`, `info`, `tip`, `success`, `question`, `warning`, `failure`, `danger`, `bug`, `example`, `quote`.

### Mise en forme
- Surligné `==texte==`, exposant `30^th^`, indice `H~2~O`.
- Emoji `:warning:`, `:white_check_mark:`. Font Awesome `:fa-database:`.
- Notes de bas de page : `texte[^1]` puis `[^1]: explication.`
- Abréviations : `*[PMS]: Property Management System`. Chaque occurrence de « PMS » affiche la définition au survol. Pratique pour le jargon métier.

### Style Obsidian
- Wikilinks : `[[Note]]`, `[[Note|texte]]`, `[[Note#Titre]]`, `[[Note^bloc]]`.
- Embed d'une note : `![[Note]]` (3 niveaux de récursion maximum).
- Référence de bloc : `^id` en fin de paragraphe, puis `[[Note^id]]`.
- Tags : `#tag`, `#parent/enfant`.

Les wikilinks ne rendent pas sur GitHub. Préfère les liens Markdown classiques `[texte](chemin.md#ancre)` si la doc peut être lue ailleurs.

## 3. Imports de fichiers

Syntaxe : `@import "chemin"` ou `<!-- @import "chemin" -->` (invisible hors MPE). Alternatives : `![](chemin)` et `![[chemin]]`. Chemins relatifs au fichier `.md`, ou URL distante.

| Fichier | Rendu |
|---|---|
| jpg, gif, png, apng, svg, bmp | image. Options `{width=400 height=200 title="..." alt="..."}` |
| `.csv` | tableau |
| `.mermaid`, `.dot`, `.puml` / `.plantuml` | diagramme (`.puml` local demande Java, donc indisponible ici) |
| `.html` | HTML embarqué |
| `.js` / `.css` / `.less` | script / style injecté |
| `.md` | Markdown embarqué |
| `.pdf` | indisponible ici (`pdf2svg` absent) |
| autre (`.sql`, `.cs`, `.vue`, `.json`) | bloc de code |

Options :
- `{line_begin=10 line_end=30}` : n'importe qu'une plage. Vérifie la plage obtenue dans la preview une fois : les bornes peuvent être décalées d'une ligne.
- `{as="vega-lite"}` : interprète le fichier comme un autre langage.
- `{code_block=true}` : affiche le contenu brut, même pour un `.md` ou un diagramme.
- `{cmd="python3"}` : exécute le fichier. Indisponible ici (`enableScriptExecution` vaut `false`).

Exemples :

```md
@import "../Secureholiday/flyway/sql/49287.0_Init.sql" {line_begin=1 line_end=40}
@import "schema/taxes.dot"
@import "captures/modale-taxes.png" {width=600 alt="Modale Taxes"}
@import "mapping-colonnes.csv"
```

Préfère un `@import` à un copier-coller quand l'extrait doit suivre le code. Préfère un copier-coller quand l'extrait documente un état passé (avant refonte).

## 4. Navigation et présentation

- `[TOC]` : table des matières générée à cet endroit. La sidebar TOC s'ouvre aussi avec `esc` dans la preview.
- Saut de page à l'export PDF : `<div style="page-break-after: always;"></div>`.
- Présentation reveal.js : front matter `presentation:` puis slides séparées par `<!-- slide -->`.

```md
---
presentation:
  theme: white.css
---

<!-- slide -->
# Refonte Taxes

<!-- slide -->
## Chaîne d'appels
```

## 5. Exports

Clic droit dans la preview :
- HTML (offline ou CDN) : fonctionne.
- PDF via Puppeteer (Chrome) : fonctionne si Chrome est trouvé.
- PDF via Prince, eBook : demandent un binaire externe, non vérifié ici.
- Pandoc (PDF, Word, RTF, Beamer) : `pandoc` absent.
- Markdown GFM : les diagrammes deviennent des PNG. Utile pour publier la doc sur un outil qui ne rend pas mermaid. `{filename="x.png"}` fixe le nom.

Personnalisation (fichiers dans `C:\Users\c.parisot\.crossnote\`) : `style.less` (CSS), `head.html`, `parser.js` (extension du parser), `config.js` (KaTeX, MathJax, mermaid). Tous sont aux valeurs par défaut.
