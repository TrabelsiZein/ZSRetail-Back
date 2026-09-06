# Module Promotions — Documentation Fonctionnelle

**Module** : POS (Point de Vente)
**Version** : 1.6.1
**Date** : Avril 2026
**Public** : Gérants de magasin, responsables, caissiers, administrateurs

---

## 1. Présentation générale

Le module **Promotions** permet de configurer et d'exploiter des remises commerciales ciblées au point de vente : remises simples sur un article ou une famille, offres à seuil de quantité (ex. « 3 achetés = 1 offert »), remises globales sur le panier, ou campagnes activées uniquement via un **code promo** saisi par le caissier.

### Principes clés

- Chaque promotion possède un **code unique** (ex. `PROMO-ETE`) qui sert à la fois d'identifiant interne et, lorsque l'option est activée, de **code promotionnel** saisi en caisse.
- Les promotions peuvent être **automatiques** (appliquées dès qu'un article éligible est ajouté au panier) ou **sur code** (nécessitent une saisie manuelle par le caissier).
- Une promotion peut viser un **article précis**, une **famille**, une **sous-famille**, ou **le panier entier**.
- Les règles de précédence garantissent qu'une seule promotion s'applique par ligne (la plus prioritaire), et une seule promotion panier globale.
- Les **champs sensibles** (type de remise, montant, scope…) sont **verrouillés** automatiquement dès qu'une promotion a été utilisée sur au moins une vente, afin de préserver la traçabilité comptable.
- Toutes les utilisations sont **traçables** via le rapport Promotions.

### Spécificités de conception

| Point | Détail |
|---|---|
| **Précédence claire** | Au **niveau ligne** : la tarification ERP (**SalesPrice** et/ou **SalesDiscount**, qui peuvent se cumuler) prime ; si aucune règle ERP ne s'applique, une **promotion POS article/famille** est cherchée ; sinon le prix de base s'applique. Les **promotions panier (CART)** s'ajoutent indépendamment, quel que soit le tarif ligne. |
| **Priorité absolue** | Entre plusieurs promotions éligibles, la valeur du champ `priorité` décide (valeur supérieure = appliquée en premier). Pas de stacking automatique. |
| **Scope spécifique** | Si plusieurs promotions s'appliquent à un même article, l'ordre de spécificité est : Article > Sous-famille > Famille. |
| **Planification fine** | Chaque promotion peut être limitée à une plage de dates, à certains jours de la semaine, et à une plage horaire (happy hour). |
| **Protection post-usage** | Une fois qu'une vente utilise la promotion, les paramètres de base (type, montant, scope, seuil) deviennent non modifiables — seuls le nom, la description, les dates et l'activation restent éditables. |

---

## 2. Types de promotions

Trois grands types sont disponibles. Le choix du type détermine les champs à renseigner et le comportement en caisse.

### 2.1 Remise simple (`SIMPLE_DISCOUNT`)

Remise appliquée automatiquement sur un article, une famille ou une sous-famille, sans seuil de quantité.

- **Cible possible** : Article, Famille, Sous-famille
- **Types d'avantage** : % de remise OU montant fixe (TND)
- **Exemple** : « 10 % de remise sur la famille Desserts »

### 2.2 Promotion à seuil de quantité (`QUANTITY_PROMOTION`)

S'active uniquement quand le client achète une **quantité minimale** du produit/famille ciblé.

- **Cible possible** : Article, Famille, Sous-famille
- **Condition** : `quantité_minimale` requise
- **Types d'avantage** :
  - % de remise sur la ligne
  - Montant fixe de remise
  - **Quantité offerte** (`FREE_QUANTITY`) → modèle « Buy N Get M Free »
- **Exemple** : « 3 bouteilles achetées = 1 offerte » → `quantité_minimale = 3`, `quantité_offerte = 1`

### 2.3 Remise sur panier (`CART_DISCOUNT`)

Remise globale appliquée sur le total du panier, typiquement conditionnée à un montant minimum.

- **Cible** : Panier entier (CART)
- **Condition optionnelle** : `montant_minimum` (TTC) sur le panier
- **Types d'avantage** : % ou montant fixe
- **Exemple** : « 20 TND de remise dès 200 TND d'achat »

---

## 3. Activation & pré-requis

### 3.1 Rôle requis

- **ADMIN** ou **RESPONSIBLE** pour créer, modifier et supprimer des promotions.
- **POS_USER** (caissier) pour saisir un code promo en caisse et bénéficier de l'application automatique.

### 3.2 Pré-requis techniques

Pour que les promotions soient évaluées en caisse :

- Les articles, familles et sous-familles ciblés doivent exister dans le référentiel.
- Le moteur de calcul de prix (`pos.pricing.enable-sales-price-group` dans le paramétrage) doit être actif — il pilote aussi l'évaluation des promotions lors de l'ajout d'articles au panier.

> ℹ️ Les promotions automatiques s'appliquent dès qu'un article éligible est scanné. Aucune configuration supplémentaire n'est nécessaire côté caissier.

---

## 4. Création & gestion des promotions (administration)

**Chemin** : `Administration` → `Stock` → `Promotions`

> [Capture à insérer — SS1 : Page de gestion des promotions]

### 4.1 Liste des promotions

La page affiche un tableau paginé avec :

- **Code** (et description en sous-titre)
- **Nom**
- **Type** (Simple / Quantité / Panier — badge coloré)
- **Cible** (scope + article / famille / sous-famille)
- **Avantage** (ex. « 15 % », « 3,50 TND », « Buy 3 Get 1 »)
- **Dates de validité**
- **Priorité**
- **Statut** (Actif / Inactif)
- **Actions** (Éditer / Désactiver / Supprimer)

**Filtres disponibles** :
- Recherche libre (code, nom, description)
- Filtre par type (SIMPLE_DISCOUNT / QUANTITY_PROMOTION / CART_DISCOUNT)

Un badge « Code requis » (cadenas jaune) signale les promotions qui ne s'activent qu'avec saisie manuelle du code en caisse.

### 4.2 Créer une nouvelle promotion

Cliquer sur **« Nouveau »** pour ouvrir le formulaire en deux étapes.

#### Étape 1 — Choisir le type

Trois cartes visuelles permettent de sélectionner le type de promotion :

> [Capture à insérer — SS2 : Sélecteur de type de promotion]

- **Remise simple** (icône étoile)
- **Promotion quantité** (icône empilée)
- **Remise panier** (icône panier)

Le choix du type conditionne les champs disponibles dans l'étape suivante.

#### Étape 2 — Formulaire détaillé

Le formulaire est organisé en deux colonnes.

> [Capture à insérer — SS3 : Formulaire de création / édition]

**Colonne gauche — Identité & Validité**

| Champ | Description |
|---|---|
| **Code** | Identifiant unique. Un bouton « Générer » propose un code automatique. |
| **Code requis en caisse** | Interrupteur. Si activé, la promotion ne s'appliquera qu'après saisie manuelle du code par le caissier. |
| **Nom** | Libellé affiché en caisse et sur le rapport. Obligatoire. |
| **Description** | Texte libre (conditions, terms & conditions…). |
| **Date de début** | Optionnelle. Avant cette date, la promotion est ignorée. |
| **Date de fin** | Optionnelle. Après cette date, la promotion n'est plus appliquée. |
| **Priorité** | Entier. Plus élevé = appliqué en premier en cas de conflit. |
| **Actif** | Interrupteur. Permet de désactiver temporairement sans supprimer. |

**Colonne droite — Scope, seuil & avantage**

Les champs de cette colonne dépendent du **type** choisi :

| Type | Champs principaux |
|---|---|
| **SIMPLE_DISCOUNT** | Scope (Article / Famille / Sous-famille), cible, type d'avantage (% ou fixe), valeur |
| **QUANTITY_PROMOTION** | Scope, cible, **quantité minimale**, type d'avantage (% / fixe / quantité offerte), valeur |
| **CART_DISCOUNT** | **Montant minimum** du panier, type d'avantage (% ou fixe), valeur |

**Section horaire (optionnelle)** — commune à tous les types :

- **Jours de la semaine** : boutons visuels MONDAY → SUNDAY, sélection multiple. Vide = tous les jours.
- **Heure de début / Heure de fin** : plage horaire (happy hour). Vide = toute la journée.

**Aperçu en direct** : une alerte affiche le calcul prévu, ex.
> « Avantage : 15 % de remise sur tous les articles de la famille *Boissons* ».

### 4.3 Éditer une promotion

Cliquer sur l'icône **✏️ Éditer** dans la liste ou depuis la fiche détail.

**Règle de verrouillage automatique** :

Si la promotion a déjà été utilisée sur au moins une vente, un bandeau d'avertissement apparaît :

> ⚠️ Cette promotion a déjà été utilisée sur X vente(s). Seuls le nom, la description, les dates, la priorité et le statut peuvent être modifiés. Les paramètres commerciaux (type, scope, avantage, seuil, code) sont verrouillés pour préserver la traçabilité comptable.

Les champs verrouillés apparaissent grisés. Toute tentative de modification sur un champ verrouillé est rejetée par le serveur (erreur 409).

**Champs toujours modifiables** :
- Nom, description
- Dates de début / fin
- Priorité
- Actif / inactif
- Jours et heures autorisés

**Champs verrouillés dès utilisation** :
- Code
- Type de promotion
- Scope et article / famille / sous-famille cible
- Type et valeur de l'avantage (%, montant, quantité offerte)
- Seuils (`quantité_minimale`, `montant_minimum`)
- Option « Code requis »

### 4.4 Désactiver une promotion

Pour **stopper** une promotion sans la supprimer (utile pour l'historique) :

- Cliquer sur l'icône **⏸ Désactiver** (pause jaune).
- Le statut passe à **Inactif** ; la promotion n'est plus évaluée en caisse.
- La ligne reste visible dans la liste et dans le rapport.

La désactivation n'efface aucune vente antérieure.

### 4.5 Supprimer une promotion

- Cliquer sur l'icône **🗑 Supprimer** (corbeille rouge).
- **Blocage automatique** : si la promotion a déjà été utilisée (`usage_count > 0`), la suppression est refusée (erreur 409) et le message suggère d'utiliser la désactivation à la place.
- Seules les promotions **jamais utilisées** peuvent être supprimées définitivement.

---

## 5. Utilisation au point de vente (flux caissier)

### 5.1 Application automatique

Lorsqu'un article est ajouté au panier, le POS interroge le moteur de calcul :

1. Si un **SalesPrice** ou **SalesDiscount** ERP s'applique → il prévaut ; aucune promotion n'est évaluée.
2. Sinon, le moteur cherche la **meilleure promotion éligible** selon l'ordre de spécificité :
   - Promotion sur l'**article** exact
   - Sinon promotion sur la **sous-famille**
   - Sinon promotion sur la **famille**
3. Entre plusieurs promotions du même niveau, la **priorité** décide.

Les promotions automatiques apparaissent directement dans les totaux de la ligne (prix, % ou montant de remise, éventuelle quantité offerte).

> [Capture à insérer — SS4 : Article du panier avec promotion appliquée]

### 5.2 Promotions sur code

Certaines promotions (option **« Code requis »**) ne s'appliquent qu'après saisie manuelle.

**Chemin** : Écran **Paiement**

> [Capture à insérer — SS5 : Panneau de saisie de code promo sur l'écran Paiement]

1. Un champ **« Code promo »** est disponible dans l'écran paiement.
2. Le caissier saisit le code (en majuscules recommandées) et valide.
3. Le backend vérifie :
   - Le code existe
   - La promotion est active
   - L'option « Code requis » est bien cochée
   - La date / heure / jour sont valides
4. Retour possibles :

| Réponse | Signification |
|---|---|
| `valid: true` | Code accepté, avantage appliqué. |
| `reason: NOT_FOUND` | Code inconnu. |
| `reason: INACTIVE` | Promotion désactivée. |
| `reason: EXPIRED` | Date de fin dépassée. |
| `reason: NOT_STARTED` | Date de début pas encore atteinte. |
| `reason: NOT_A_PROMO_CODE` | Code appartient à une promotion automatique (ne requiert pas de saisie). |

5. Le code validé apparaît sous forme de **badge** dans le panneau ; il peut être retiré d'un clic (×).
6. Selon le scope, les articles concernés sont **recalculés** automatiquement :
   - Scope ITEM / FAMILY / SUBFAMILY → recalcul de chaque ligne éligible.
   - Scope CART → recalcul du total global via le moteur.

### 5.3 Affichage de la remise panier

Si la promotion active est de type **CART_DISCOUNT**, un bloc vert s'affiche dans le récapitulatif de paiement :

> [Capture à insérer — SS6 : Panneau récapitulatif avec remise panier visible]

- Nom de la promotion
- Montant déduit en vert (`- X,XXX TND`)
- Pourcentage effectif entre parenthèses, le cas échéant

### 5.4 Finalisation de la vente

À la validation du paiement :

- Chaque ligne porte, le cas échéant, une référence à la promotion appliquée (`SalesLine.promotion_id` + `discount_source = "PROMOTION"`).
- La promotion panier est stockée sur l'en-tête (`SalesHeader.promotion_id`).
- Les lignes avec **quantité offerte** génèrent une ligne supplémentaire gratuite (`FREE_QUANTITY`) visible sur le ticket.
- Le ticket imprimé affiche :
  - Le libellé de la promotion sur la ligne concernée
  - Le **code** (si `requires_code = true`) dans les notes de la ligne
  - Le montant de remise panier dans le récapitulatif

### 5.5 Retours

Lorsqu'un ticket contenant des promotions est retourné :

- Les lignes retournées conservent leur référence à la promotion (traçabilité).
- Aucun impact sur la promotion elle-même (pas de décompte d'usage à décrémenter).
- Le rapport Promotions ne comptabilise que les ventes finalisées ; les annulations ne sont pas double-comptées.

---

## 6. Règles de précédence & priorité

### 6.1 Hiérarchie de calcul — niveau ligne (article)

La règle n'est **pas** une simple chaîne à quatre niveaux : elle fonctionne en deux **paliers**, avec cumul possible à l'intérieur du premier palier.

**Palier 1 — Tarification ERP** (SalesPrice et/ou SalesDiscount)

- **SalesPrice** et **SalesDiscount** sont évalués **en parallèle** : si les deux existent, ils s'appliquent ensemble (le prix spécial **puis** la remise ERP en pourcentage).
- Dès que **l'un ou l'autre** s'applique, les **promotions POS ligne sont ignorées**.

**Palier 2 — Promotion POS** (scope ITEM / SUBFAMILY / FAMILY)

- Évalué uniquement lorsque **ni SalesPrice ni SalesDiscount** ne s'appliquent.
- Sélectionne la meilleure promotion éligible selon les règles de spécificité et de priorité (§ 6.3).

**Palier 3 — Prix de base**

- Utilisé en dernier recours si aucune règle ne s'applique.

En résumé :

| Situation ligne | Prix appliqué | Remise appliquée | Promotion POS ligne ? |
|---|---|---|---|
| SalesPrice seul | Prix ERP spécial | — | ❌ ignorée |
| SalesDiscount seul | Prix de base | % remise ERP | ❌ ignorée |
| SalesPrice **+** SalesDiscount | Prix ERP spécial | % remise ERP | ❌ ignorée |
| Aucun ERP, promotion éligible | Prix de base | Selon promotion (%, fixe, quantité offerte) | ✅ appliquée |
| Aucun ERP, aucune promotion | Prix de base | — | — |

### 6.2 Promotion panier — indépendante

Les promotions de scope **CART** sont traitées **séparément et en plus** du calcul ligne par ligne. Elles :

- s'appliquent sur le **total du panier après** application des règles de ligne (SalesPrice / SalesDiscount / promotion article / prix de base) ;
- **ne sont pas bloquées** par la présence d'un SalesPrice ou SalesDiscount sur les lignes — une remise panier peut donc s'ajouter même si chaque ligne bénéficie déjà d'un tarif ERP spécial ;
- sont limitées à **une seule** promotion panier gagnante par ticket (la plus prioritaire éligible).

> 📌 Conséquence pratique : un client peut cumuler *tarif ERP spécial sur chaque article* **+** *remise panier globale* lors d'une même vente. En revanche, il ne cumule jamais deux promotions sur la même ligne.

### 6.3 Sélection de la promotion gagnante (niveau ligne)

Lorsque plusieurs promotions sont éligibles pour un même article, le moteur les filtre d'abord :

- `active = true`
- Date du jour comprise entre `start_date` et `end_date`
- Heure courante dans la plage `time_start` – `time_end`
- Jour courant dans la liste `day_of_week`
- Quantité en panier ≥ `quantité_minimale` (si renseignée)
- Total panier ≥ `montant_minimum` (scope CART)
- Si `requires_code = true`, le code doit être présent dans les codes saisis par le caissier

Puis il sélectionne :

1. **Scope le plus spécifique** d'abord : ITEM > SUBFAMILY > FAMILY
2. À niveau égal : **priorité** la plus élevée (absolue, pas de best-value)
3. En cas d'ex-æquo de priorité : `discountPercentage` > `discountAmount` > `freeQuantity`

> ⚠️ Pas de stacking : **une seule promotion** s'applique par ligne, et **une seule promotion panier** s'applique par ticket.

### 6.4 Exemple concret

Configuration :
- Promotion P1 : famille *Boissons*, 10 % de remise, priorité = 0.
- Promotion P2 : article *Eau 1,5 L*, 15 % de remise, priorité = 5.

Lorsqu'un client ajoute une bouteille *Eau 1,5 L* :
- Les deux promotions matchent.
- Scope ITEM (P2) bat scope FAMILY (P1) → P2 gagne.
- Le client bénéficie de **15 %** sur cette ligne.

Si le client ajoute en plus un *Jus d'orange 1 L* (même famille) :
- Seule P1 match → 10 % appliqué sur cette ligne.

---

## 7. Rapport Promotions

**Chemin** : `Rapports` → `Promotions`

> [Capture à insérer — SS7 : Rapport Promotions avec graphique et tableau]

### 7.1 Filtres

- **Date de début** et **date de fin** : période d'analyse.
- **Charger** : exécute la requête.
- **Exporter Excel** et **Imprimer** : disponibles après chargement.

### 7.2 Indicateurs (cartes de synthèse)

- **Nombre de promotions utilisées** sur la période
- **Tickets ayant bénéficié d'une promotion**
- **Total des remises accordées** (TND, couleur avertissement)
- **Chiffre d'affaires influencé** par les promotions (TND, couleur succès)

### 7.3 Tableau détaillé

Colonnes :

| Colonne | Description |
|---|---|
| **Code promotion** | Code unique |
| **Nom** | Libellé |
| **Type** | Simple / Quantité / Panier |
| **Nb. tickets** | Nombre de tickets distincts ayant utilisé la promotion |
| **Remise totale** | Somme des montants de remise (en gras, couleur avertissement) |
| **CA influencé** | Total TTC avant remise sur les lignes/tickets concernés |
| **Taux de remise** | `Remise totale / CA influencé × 100` |

Un **graphique en barres** (vue-apex-charts) illustre la répartition.

### 7.4 Logique d'agrégation

- Le rapport fusionne :
  - l'usage **ligne** (`SalesLine.promotion_id`) — promotions ITEM / FAMILY / SUBFAMILY
  - l'usage **en-tête** (`SalesHeader.promotion_id`) — promotions CART
- Seuls les tickets **complétés** (pas les tickets en attente ou annulés) sont pris en compte.
- Le nombre de tickets est `COUNT(DISTINCT ticket)` — un ticket utilisant deux promotions compte une fois pour chacune.

---

## 8. Sécurité, rôles et permissions

| Action | ADMIN | RESPONSIBLE | POS_USER |
|---|:-:|:-:|:-:|
| Consulter la liste des promotions | ✅ | ✅ | ❌ |
| Créer / éditer / désactiver une promotion | ✅ | ✅ | ❌ |
| Supprimer une promotion (si non utilisée) | ✅ | ✅ | ❌ |
| Bénéficier des promotions automatiques en caisse | ✅ | ✅ | ✅ |
| Saisir un code promo en caisse | ✅ | ✅ | ✅ |
| Consulter le rapport Promotions | ✅ | ✅ | ❌ |
| Exporter / imprimer le rapport | ✅ | ✅ | ❌ |

Les endpoints de **calcul de prix** et de **validation de code** sont accessibles à tous les rôles connectés au POS, afin que la caisse fonctionne pour tous les utilisateurs.

---

## 9. Bonnes pratiques

1. **Choisir le type adapté** : n'utilisez pas une promotion CART pour remplacer une remise article — la précédence et le reporting n'en souffriraient pas, mais la lisibilité caisse si.

2. **Prioriser avec parcimonie** : laissez `priorité = 0` par défaut. N'utilisez des priorités supérieures que pour arbitrer les cas d'ex-æquo fréquents.

3. **Tester les horaires restreints** avant lancement : créez la promotion avec `start_date = date future` et vérifiez qu'elle n'apparaît pas avant.

4. **Préférer la désactivation à la suppression** pour une promotion ayant servi — la suppression est de toute façon refusée, mais la désactivation garde l'historique propre.

5. **Ne jamais modifier les bases d'une promotion en cours** : même si ce n'était pas verrouillé, changer un taux alors que des ventes sont en cours fausserait le rapport.

6. **Utiliser les codes pour les campagnes ciblées** (emailing, influenceurs) — `requires_code = true` permet de distribuer un code unique et de mesurer précisément son impact dans le rapport.

7. **Vérifier régulièrement le rapport Promotions** pour identifier les promotions « dormantes » (0 ticket) ou celles qui rognent excessivement la marge (taux de remise > seuil tolérable).

8. **Documenter les conditions dans la description** : la description est visible à l'édition et aide les équipes à comprendre l'intention de la promotion.

9. **Pour une promotion récurrente annuelle**, préférez créer une nouvelle ligne par édition plutôt que ré-activer l'ancienne — le rapport distinguera ainsi les éditions.

---

## 10. Glossaire

| Terme | Définition |
|---|---|
| **Promotion** | Règle commerciale qui attribue une remise ou une quantité offerte selon des conditions. |
| **Code promo** | Code unique qui, lorsque `requires_code = true`, doit être saisi par le caissier pour activer la promotion. |
| **Scope** | Cible de la promotion : article, famille, sous-famille ou panier. |
| **Priorité** | Valeur entière qui départage les promotions éligibles à un même niveau de scope. Plus grand = gagnant. |
| **Verrouillage post-usage** | Protection automatique : les champs sensibles d'une promotion deviennent non modifiables dès qu'une vente l'a utilisée. |
| **Stacking** | Cumul de plusieurs promotions sur une même ligne. **Non supporté** : une seule promotion gagne par ligne. |
| **Happy hour** | Promotion restreinte à une plage horaire (`time_start` / `time_end`) et/ou à certains jours (`day_of_week`). |
| **Buy N Get M Free** | Promotion de type `QUANTITY_PROMOTION` avec `benefitType = FREE_QUANTITY` : achat de `minimumQuantity` unités déclenche `freeQuantity` gratuite(s). |
| **discount_source** | Étiquette stockée sur chaque ligne indiquant l'origine de la remise : `PROMOTION`, `MANUAL`, `SALES_PRICE`, `SALES_DISCOUNT`. |
| **CA influencé** | Chiffre d'affaires TTC des lignes ou tickets ayant bénéficié d'une promotion, avant déduction de celle-ci. |
| **millimes** | 1 TND = 1 000 millimes. Unité utilisée pour les petites valeurs (pas directement dans la configuration des promotions, qui utilisent le TND). |

---

*Fin du document.*
