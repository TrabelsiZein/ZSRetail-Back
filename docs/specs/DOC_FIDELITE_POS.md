# Programme de Fidélité — Documentation Fonctionnelle

**Module** : POS (Point de Vente)
**Version** : 1.6.0
**Date** : Avril 2026
**Public** : Gérants de magasin, responsables, caissiers, administrateurs

---

## 1. Présentation générale

Le module **Fidélité** permet à votre enseigne de récompenser ses clients réguliers en leur attribuant des **points** à chaque achat. Ces points peuvent ensuite être convertis en **remise immédiate** lors d'un prochain passage en caisse.

### Principes clés

- Un **membre fidélité** possède une **carte** (numéro unique auto-généré au format `LYL-000001`).
- Il **gagne** des points proportionnellement au montant de ses achats.
- Il peut **utiliser** (redeem) ses points pour payer une partie d'une vente future.
- En cas de **retour** de marchandise, les points gagnés sur la vente d'origine sont automatiquement **annulés**.
- Chaque mouvement de points est tracé dans un **journal d'audit immuable** (aucune suppression, aucune modification possible après coup).

### Spécificités de conception

| Point | Détail |
|---|---|
| **Indépendant du module Client** | Un porteur de carte peut être un client "de passage" ou être rattaché à une fiche Client ERP existante (lien optionnel). |
| **Création à la volée** | Le caissier peut créer une nouvelle carte de fidélité directement depuis l'écran de vente, en quelques secondes. |
| **Programme versionné** | Les paramètres du programme (taux, plafonds, expiration) sont historisés : un seul programme actif à la fois, les anciens restent consultables. |
| **Audit complet** | Tous les mouvements (gain, utilisation, ajustement, annulation) sont enregistrés avec solde avant / après. |

---

## 2. Activation du module

### 2.1 Pré-requis

- Rôle **ADMIN** ou **RESPONSIBLE**.
- Licence POS valide incluant le module Fidélité.

### 2.2 Activer la fidélité

**Chemin** : `Administration` → `Paramètres Généraux (General Setup)`

1. Rechercher la ligne **`LOYALTY_ENABLED`** dans la liste des paramètres.
2. Basculer le toggle sur **Activé (true)**.
3. Enregistrer.

> [Capture à insérer — SS1 : Page General Setup avec le toggle LOYALTY_ENABLED]

> ⚠️ Tant que `LOYALTY_ENABLED` est désactivé :
> - Le bouton **★ Fidélité** n'apparaît pas à la caisse.
> - Les menus Fidélité restent visibles pour les admins afin de préparer la configuration, mais **aucun point n'est attribué** lors des ventes.

### 2.3 Créer le premier programme de fidélité

L'activation du toggle ne suffit pas : il faut également **créer un programme actif** décrivant les règles de gain et d'utilisation.

**Chemin** : `Ventes` → `Programmes de Fidélité (Loyalty Programs)`

> [Capture à insérer — SS2 : Liste des programmes de fidélité]

Cliquer sur **"Nouveau programme"** et renseigner :

| Champ | Description | Exemple |
|---|---|---|
| **Code programme** | Identifiant unique du programme | `PROG-2026` |
| **Nom** | Nom affiché | `Programme Standard 2026` |
| **Description** | Texte libre | `1 TND dépensé = 10 points` |
| **Date de début** | Date d'entrée en vigueur | `01/01/2026` |
| **Date de fin** | Optionnelle | `31/12/2026` |
| **Points par dinar** (`points_per_dinar`) | Nombre de points gagnés par 1 TND dépensé | `10` |
| **Valeur d'un point en millimes** (`point_value_millimes`) | Valeur monétaire d'un point lors de l'utilisation | `10` (1 pt = 10 millimes = 0,010 TND) |
| **Points minimum pour utilisation** | Solde minimum requis pour pouvoir utiliser | `100` |
| **% maximum d'utilisation** | Part maximale de la vente payable en points | `30` (max 30 % du total) |
| **Durée de validité (jours)** | `null` = sans expiration | `365` |

> [Capture à insérer — SS3 : Formulaire de création d'un programme]

### 2.4 Règles d'activation des programmes

- **Un seul programme actif à la fois.** La création d'un nouveau programme actif désactive automatiquement l'ancien.
- Les programmes **passés sont immuables** : on ne peut pas les modifier ni les supprimer (traçabilité comptable).
- Pour changer les taux, il faut **créer un nouveau programme** (versioning).

### 2.5 Exemple de paramétrage

Avec les valeurs ci-dessus :

- Un client achète pour **50 TND** → il gagne **50 × 10 = 500 points**.
- Il souhaite utiliser ses points sur une prochaine vente de **100 TND** :
  - Plafond 30 % → max **30 TND** payables en points.
  - 30 TND = 30 000 millimes → max **3 000 points** utilisables.
  - S'il utilise 1 000 points → déduction de **10 TND** sur la vente.

---

## 3. Gestion des membres

**Chemin** : `Ventes` → `Membres Fidélité (Loyalty Members)`

### 3.1 Liste des membres

La page affiche un tableau paginé avec :

- Numéro de carte
- Nom complet
- Téléphone
- Solde points actuel
- Statut (Actif / Inactif)
- Actions (Voir / Éditer / Activer-Désactiver)

**Filtres disponibles** :
- Recherche libre (numéro de carte, nom, téléphone, email)
- Filtre par statut (Tous / Actif / Inactif)

> [Capture à insérer — SS4 : Liste paginée des membres fidélité]

### 3.2 Créer un nouveau membre

Cliquer sur **"+ Nouveau membre"**. Champs du formulaire :

| Champ | Obligatoire | Note |
|---|---|---|
| Numéro de carte | Auto-généré | Format `LYL-000001`, incrémental |
| Prénom | Oui | |
| Nom | Oui | |
| Téléphone | Recommandé | Sert de moyen de recherche rapide en caisse |
| Email | Non | |
| Date de naissance | Non | Permet de futures campagnes anniversaire |
| Client lié (ERP) | Non | Lien optionnel vers une fiche Client existante |

> [Capture à insérer — SS5 : Formulaire de création d'un membre]

### 3.3 Fiche détaillée d'un membre

Le clic sur un membre ouvre une fiche contenant :

- **Informations personnelles** (modifiables).
- **Résumé des points** :
  - Solde actuel
  - Total gagné (historique cumulé)
  - Total utilisé (historique cumulé)
  - Valeur du solde en dinars (calcul basé sur le programme actif)
- **5 dernières transactions** avec bouton **"Voir tout"** → redirige vers la page des transactions filtrée sur ce membre.
- **Bouton "Ajuster points"** : réservé aux administrateurs (voir §5.3).

> [Capture à insérer — SS6 : Fiche détail d'un membre]

### 3.4 Désactiver un membre

Un membre désactivé :
- Ne peut plus gagner de points.
- Ne peut plus utiliser ses points.
- Reste visible dans l'historique et les rapports.

La désactivation n'efface **jamais** les transactions passées.

---

## 4. Utilisation au point de vente (flux caissier)

### 4.1 Écran de sélection des articles

Lorsque `LOYALTY_ENABLED = true`, un bouton **★ Fidélité** apparaît dans la barre d'actions de l'écran de vente.

> [Capture à insérer — SS8 : Écran ItemSelection avec bouton ★ Fidélité]

### 4.2 Identifier ou créer le membre

Le clic sur **★ Fidélité** ouvre une fenêtre modale avec deux onglets :

1. **Rechercher** : par numéro de carte, nom ou téléphone.
2. **Créer** : formulaire rapide (nom, prénom, téléphone) → génère immédiatement une nouvelle carte.

> [Capture à insérer — SS9 : Modal de recherche/création en caisse]

Une fois le membre sélectionné, un **badge** apparaît dans le panier affichant :
- Le nom du membre
- Son solde de points actuel
- Un lien pour le retirer de la vente

> [Capture à insérer — SS10 : Badge fidélité dans le panier]

### 4.3 Paiement et utilisation des points

Sur l'écran **Paiement** (Payment), un panneau dédié à la fidélité s'affiche si un membre est rattaché :

- **Solde disponible** : X points (≈ Y TND)
- **Champ "Points à utiliser"** : saisie libre, validée en temps réel contre :
  - le minimum d'utilisation du programme (`minimum_redemption_points`)
  - le pourcentage maximum autorisé (`maximum_redemption_percentage`)
  - le solde actuel du membre
- **Déduction fidélité** : calculée automatiquement et ajoutée à la ligne de récapitulatif.

> [Capture à insérer — SS11 : Panneau fidélité sur l'écran Paiement]

### 4.4 Finalisation de la vente

À la validation de la vente :

- Les **points gagnés** sont crédités (selon `points_per_dinar` appliqué au total TTC).
- Les **points utilisés** sont débités.
- Une ligne **`EARNED`** et, le cas échéant, une ligne **`REDEEMED`** sont ajoutées au journal d'audit.
- Le ticket imprimé contient un **bloc fidélité** :

  ```
  ─────── FIDÉLITÉ ───────
  Carte       : LYL-000042
  Client      : Jean Dupont
  Points gagnés       : +500
  Points utilisés     : -1000
  Montant déduit      : 10,000 TND
  Nouveau solde       : 1500 pts
  ─────────────────────────
  ```

> [Capture à insérer — SS12 : Ticket avec bloc fidélité]

> 📌 Le bloc fidélité est affiché uniquement sur les **tickets de vente standard**. Il n'apparaît pas sur les bons d'achat ni les tickets de garantie.

### 4.5 Retours et annulation de points

Lorsqu'un ticket contenant des points est retourné (totalement ou partiellement) :

- Une ligne **`REVERSED`** est créée automatiquement.
- Les points précédemment gagnés sont **débités** du solde du membre.
- Si la vente d'origine avait aussi consommé des points, ceux-ci sont **re-crédités**.

Le membre voit donc son solde revenir à l'état antérieur à la vente annulée.

---

## 5. Audit & administration

### 5.1 Journal des transactions

**Chemin** : `Ventes` → `Transactions Fidélité (Loyalty Transactions)`

Page d'audit globale, **tous membres confondus**, avec :

- **Filtres** :
  - Recherche (carte / nom / téléphone)
  - Type : `EARNED` / `REDEEMED` / `ADJUSTED` / `REVERSED` / Tous
  - Période (date de début / date de fin)
- **Colonnes** : Date, Membre (carte + nom, cliquable), Type, Points, Solde avant, Solde après, Description, Référence vente/retour.
- **Pagination** configurable (10 / 25 / 50 / 100).

> [Capture à insérer — SS13 : Page des transactions fidélité]

### 5.2 Types de transactions

| Type | Origine | Effet sur solde |
|---|---|---|
| **EARNED** | Vente finalisée | + points |
| **REDEEMED** | Vente finalisée avec utilisation | − points |
| **REVERSED** | Retour de marchandise | ± (annulation d'une ligne antérieure) |
| **ADJUSTED** | Ajustement manuel par un admin | + ou − |

### 5.3 Ajustement manuel (réservé Admin)

Depuis la fiche d'un membre, bouton **"Ajuster points"** :

- Saisir un **delta** positif (offert) ou négatif (retrait).
- Saisir une **description obligatoire** (raison).
- L'opération crée une ligne `ADJUSTED` signée par l'utilisateur courant (`created_by`).

> [Capture à insérer — SS7 : Modal d'ajustement de points]

**Cas d'usage typiques** :
- Geste commercial (client mécontent).
- Correction d'une erreur de saisie ancienne.
- Campagne ponctuelle (bonus anniversaire).

### 5.4 Expiration des points

Si le programme actif définit un `points_expiry_days` (par exemple 365) :

- Chaque lot de points gagnés porte une **date d'expiration**.
- Les points non utilisés au-delà de cette date **sortent du solde disponible** (une ligne d'expiration est journalisée).
- Si `points_expiry_days = null`, les points ne périment jamais.

---

## 6. Rapports

**Chemin** : `Rapports` → `Fidélité (Loyalty Report)`

Le rapport Fidélité permet d'analyser la performance du programme :

- **Filtres** : plage de dates, granularité (jour / semaine / mois).
- **Indicateurs** :
  - Points gagnés sur la période
  - Points utilisés sur la période
  - Nombre de membres actifs ayant transigé
- **Visualisation** : graphique (barres / lignes / aires) + tableau paginé.
- **Options** : tri (valeur ↓↑, libellé A→Z / Z→A), export.

> [Capture à insérer — SS14 : Rapport Fidélité avec graphique]

---

## 7. Sécurité, rôles et permissions

| Action | ADMIN | RESPONSIBLE | POS_USER |
|---|:-:|:-:|:-:|
| Activer/désactiver le module (`LOYALTY_ENABLED`) | ✅ | ❌ | ❌ |
| Créer un programme | ✅ | ✅ | ❌ |
| Consulter les programmes | ✅ | ✅ | ❌ |
| Créer / éditer un membre (back-office) | ✅ | ✅ | ❌ |
| Créer un membre à la caisse | ✅ | ✅ | ✅ |
| Utiliser les points en vente | ✅ | ✅ | ✅ |
| Ajuster manuellement les points | ✅ | ✅ | ❌ |
| Consulter le journal global | ✅ | ✅ | ❌ |
| Rapport fidélité | ✅ | ✅ | ❌ |

---

## 8. Bonnes pratiques

1. **Définir le programme avant d'activer le toggle** pour éviter les ventes sans règles.
2. **Communiquer clairement aux caissiers** le plafond d'utilisation (% maxi) pour qu'ils expliquent au client.
3. **Ne jamais modifier manuellement la base** pour corriger un solde : toujours passer par **Ajuster points** (traçabilité).
4. **Vérifier régulièrement le rapport Fidélité** pour suivre le ratio points gagnés vs utilisés (indicateur de santé du programme).
5. **En cas de changement de taux**, créer un nouveau programme ; ne jamais tenter de "réactiver" un ancien programme.

---

## 9. Glossaire

| Terme | Définition |
|---|---|
| **Membre fidélité** | Client porteur d'une carte, distinct de la fiche Client ERP. |
| **Programme** | Jeu de règles (taux, plafonds, expiration) actif à un moment donné. |
| **Points gagnés (EARNED)** | Crédit automatique proportionnel au montant d'une vente. |
| **Points utilisés (REDEEMED)** | Débit volontaire à la caisse, converti en remise. |
| **Ajustement (ADJUSTED)** | Opération manuelle admin, positive ou négative. |
| **Annulation (REVERSED)** | Effet automatique d'un retour sur la vente d'origine. |
| **Solde avant / après** | Photographie du solde du membre de part et d'autre d'une transaction. |
| **millimes** | 1 TND = 1 000 millimes. |

---

*Fin du document.*
