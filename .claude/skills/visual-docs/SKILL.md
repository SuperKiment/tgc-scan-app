---
name: visual-docs
description: Rédiger de la documentation Markdown visuelle et compréhensible, rendue dans VS Code avec Markdown Preview Enhanced (MPE) : diagrammes (mermaid, dot, PlantUML/d2 via Kroki, vega-lite, wavedrom), alertes, admonitions, imports de fichiers, maths. Utilise ce skill dès que tu écris ou restructures un `.md` de documentation, même si l'utilisateur ne parle pas de diagramme. Cas typiques : doc d'architecture, cartographie de projet ou de base de données, chaîne d'appels, flux de données, recherche préparatoire à une tâche, notes de tâche (DOC_CURRENT_TASK, PLAN, GOAL), comparatif ancien/nouveau, README interne. À charger aussi quand l'utilisateur demande « documente », « fais un schéma », « explique visuellement » ou « rends ça plus lisible ».
---

# Documentation visuelle (Markdown Preview Enhanced)

La doc est lue dans **VS Code avec Markdown Preview Enhanced 0.8.40** (MPE, moteur crossnote). Utilise toute la syntaxe que MPE rend, mais pas les fonctions qui échouent sur ce poste (voir « Ce qui ne rend pas »).

But : un lecteur qui arrive à froid doit comprendre le mécanisme vite. Un visuel se justifie quand il montre une structure que la prose rend mal : relations, ordre, flux, états, comparaisons. Un diagramme décoratif ou qui répète un paragraphe est du bruit. Retire-le.

## Choisir le bon outil

Pars du contenu, pas de l'outil :

| Contenu à expliquer | Outil | Bloc |
|---|---|---|
| Chaîne d'appels, échange front/back/DB, ordre des messages | mermaid `sequenceDiagram` | ` ```mermaid ` |
| Flux de décision, pipeline, étapes d'un traitement | mermaid `flowchart` | ` ```mermaid ` |
| Tables et relations (schéma SQL) | mermaid `erDiagram` (ou DBML via Kroki si beaucoup de colonnes) | ` ```mermaid ` |
| Cycle de vie, statuts d'une entité | mermaid `stateDiagram-v2` | ` ```mermaid ` |
| Classes, DTO, héritage | mermaid `classDiagram` | ` ```mermaid ` |
| Planning, phases | mermaid `gantt` | ` ```mermaid ` |
| Graphe de dépendances dense (projets, procédures stockées), layout auto important | Graphviz | ` ```dot ` ou ` ```viz ` |
| Architecture C4, composants, déploiement, séquences très détaillées | PlantUML via Kroki | ` ```plantuml {kroki=true} ` |
| Architecture lisible, conteneurs imbriqués | d2 via Kroki | ` ```d2 {kroki=true} ` |
| Chiffres : volumes, répartition, évolution | vega-lite | ` ```vega-lite ` |
| Chronogramme de signaux, champs de bits | wavedrom / bitfield | ` ```wavedrom ` |
| Formule, calcul de taxe, règle d'arrondi | KaTeX | `$...$`, `$$...$$` |
| Comparatif ancien/nouveau, mapping de colonnes | tableau GFM | — |
| Piège, décision bancale, prérequis | alerte `> [!WARNING]` ou admonition | — |
| Extrait de code réel (SQL, C#, Vue) | `@import` avec plage de lignes, ou bloc avec `{highlight=...}` | — |

Ordre de préférence : **mermaid d'abord**. Il est rendu localement, sans réseau, et aussi sur GitHub et dans la plupart des outils. Passe à Graphviz quand mermaid produit un layout illisible (plus de ~15 nœuds très connectés). Passe à Kroki (PlantUML, d2, DBML, etc.) seulement quand mermaid n'a pas le type de diagramme ou rend mal.

Syntaxe détaillée et exemples copiables :
- Diagrammes : `references/diagrams.md`. Lis la section de l'outil choisi avant d'écrire un diagramme hors mermaid simple.
- Syntaxe Markdown étendue, imports, exports : `references/markdown-mpe.md`.

## Ce qui ne rend pas sur ce poste

Ces fonctions affichent une erreur ou un bloc brut. Ne les utilise pas sans demande explicite :
- ` ```puml ` / ` ```plantuml ` **sans** `{kroki=true}` : pas de Java, pas de `plantumlServer`. Écris toujours ` ```plantuml {kroki=true} `. Le nom `puml` ne marche pas avec Kroki : écris `plantuml`.
- ` ```d2 ` **sans** `{kroki=true}` : binaire `d2` absent.
- Code chunks exécutables (` ```python {cmd=true} `, matplotlib, plotly, `@import ... {cmd=...}`) : `enableScriptExecution` vaut `false`. Pour un graphique, utilise vega-lite.
- `@import "x.pdf"` : `pdf2svg` absent.
- Exports Pandoc (Word, RTF, Beamer) : `pandoc` absent. Les exports HTML et PDF (Puppeteer) fonctionnent.
- Tableaux à cellules fusionnées (`enableExtendedTableSyntax`) et CriticMarkup : désactivés.

Kroki (`kroki.io`), WebSequenceDiagrams et tikzjax.com envoient le contenu du bloc à un service externe. L'utilisateur l'autorise. Ces blocs demandent le réseau pour rendre.

## Règles de rédaction

- **Un visuel = une idée.** Titre court au-dessus, une ou deux phrases dessous qui disent quoi regarder (« le point clé : le WS appelle la proc deux fois »).
- **Diagramme ≤ ~15 nœuds.** Au-delà, découpe en vue d'ensemble plus vues de détail. Un diagramme illisible coûte plus cher qu'un paragraphe.
- **Vrais noms.** Utilise les noms exacts du code (classes, procs, tables, routes) dans les nœuds. Le lecteur doit pouvoir les chercher dans le code.
- **Liens vers les sources.** Pointe les fichiers réels par chemin relatif ou lien, quand ils ne vont pas bouger. Pour un extrait qui doit rester à jour, préfère `@import "chemin" {line_begin=.. line_end=..}` au copier-coller.
- **Alertes pour ce qui compte.** `> [!WARNING]` pour un piège, `> [!NOTE]` pour un contexte, `> [!TIP]` pour un raccourci. Ne mets pas tout en alerte : l'effet disparaît.
- **Tableaux pour les comparaisons.** Ancien/nouveau, colonne source/colonne cible, cas/résultat attendu.
- **Navigation.** `[TOC]` en tête des docs longues. Ancres `{#id}` sur les titres cités ailleurs. Notes de bas de page pour les digressions.
- **Retour à la ligne.** `breakOnSingleNewLine` vaut `true` : un simple saut de ligne fait un retour à la ligne dans la preview. N'écris pas de paragraphe coupé en plusieurs lignes, sauf si tu veux ces retours à la ligne.
- **Conventions du projet en priorité.** Si le dépôt impose un format (index de lignes en tête, langue, emplacement), il prime sur ce skill.

## Avant de rendre la doc

1. Chaque diagramme répond à une question précise et a une phrase d'explication.
2. Aucun bloc de la liste « Ce qui ne rend pas ».
3. Syntaxe des diagrammes vérifiée : guillemets autour des libellés mermaid qui contiennent `()`, `:`, `/`, `{}` ou des accents ; pas de mot réservé (`end`) comme id de nœud mermaid.
4. Les chemins des `@import` et des liens existent (vérifie-les avec `ls`).
5. Dis à l'utilisateur quels blocs demandent le réseau (Kroki, wsd, tikz), au cas où il lit hors ligne.
