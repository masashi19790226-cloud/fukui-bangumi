# 福井番組表（個人用 Android アプリ）

福井ケーブルテレビ（FCTV）の契約者が、スマホで「今なにを放送しているか」をすぐ確認するための個人用アプリです。
番組データの取得もアプリのビルドも、すべて **GitHub の上で自動**で行います。パソコンに Android Studio を入れる必要はありません。

- 今放送中の一覧（進み具合のバー・次の番組つき）
- 番組表グリッド（今日を含む7日分、ジャンル別の色分け、赤い線が現在時刻）
- キーワード検索
- 放送前通知（番組ごと・キーワードごと、5分前・10分前・30分前から選択）

## しくみ

```
NHK番組表API ──(3時間ごと)──> GitHub Actions ──> gh-pages（番組JSON 7日分）──> スマホのアプリ
```

| ワークフロー | 役割 |
| --- | --- |
| 番組表を更新（`.github/workflows/epg.yml`） | 3時間ごとに今日・明日分を取り直す。日本時間3時の回と手動実行では7日分すべて取り直す |
| アプリをビルド（`.github/workflows/android.yml`） | `main` にアプリのコードが push されると APK を作り、「Releases」に置く |

## いま取得できるチャンネル

| 放送波 | チャンネル | 取得元 |
| --- | --- | --- |
| 地デジ | NHK総合・福井、NHK Eテレ | NHK番組表API（公式） |
| BS | NHK BS | NHK番組表API（公式） |

**福井放送・福井テレビ・BS民放・CS専門チャンネルは入っていません。**
Gガイド（bangumi.org）などの番組表サイトは、利用規約で自動収集（スクレイピング等）を禁止しているためです。
あとから正当な取得元（自宅のテレビチューナーなど）が用意できたら、`fetcher/channels.json` にチャンネルを足すだけで追加できます（下の「チャンネルを追加する」を参照）。

---

## セットアップ手順（初回だけ・30分ほど）

### 1. NHK番組表APIのキーを取る

1. [NHK API ポータル](https://api-portal.nhk.or.jp/) で利用登録をする
2. 「アプリ」を登録すると **APIキー** が発行されるので控えておく
3. 利用規約（出典の表示や回数制限など）を確認しておく

### 2. GitHub にリポジトリを作る

1. [GitHub](https://github.com/) にログインし、右上の「＋」→「New repository」
2. Repository name：例 `fukui-bangumi`
3. **Public** を選ぶ（無料プランで GitHub Pages を使うため。APIキーは公開されません）
4. 「Create repository」

### 3. ファイルをアップロードする

おすすめは [GitHub Desktop](https://desktop.github.com/) です。

1. GitHub Desktop でリポジトリを Clone する
2. 開いたフォルダに、この zip の中身（`app`、`fetcher`、`.github` など全部）をコピーする
3. 「Commit to main」→「Push origin」

> ブラウザでアップロードする場合は「uploading an existing file」に、zip の中身を**フォルダごと**ドラッグしてください。`.github` フォルダも忘れずに入れてください。これが無いと自動処理が動きません。

### 4. APIキーを GitHub に登録する

1. リポジトリの「Settings」→「Secrets and variables」→「Actions」
2. 「New repository secret」
3. Name：`NHK_API_KEY`、Secret：手順1のキー →「Add secret」

### 5. 番組表の取得を動かす

1. 「Actions」タブを開く（確認が出たら「I understand my workflows, go ahead and enable them」）
2. 左の「番組表を更新」→「Run workflow」→ 緑の「Run workflow」
3. 1〜2分で緑のチェックになれば成功です。`gh-pages` ブランチができます

### 6. GitHub Pages を有効にする

1. 「Settings」→「Pages」
2. Source：**Deploy from a branch**、Branch：**gh-pages** ／ **/(root)** →「Save」
3. 数分後、`https://<ユーザー名>.github.io/<リポジトリ名>/epg/index.json` をブラウザで開いてJSONが出ればOK

### 7. アプリをビルドしてスマホに入れる

1. 「Actions」→「アプリをビルド」→「Run workflow」（最初の push でも自動で動いています）
2. 5分ほどで完了すると、リポジトリのトップ右側の「Releases」に `fukui-bangumi.apk` が並びます
3. **スマホのブラウザ**でその Releases ページを開いて APK をダウンロードし、インストールします
   （「この提供元のアプリを許可」を求められたら許可してください）
4. 初回起動で通知を許可します。時間ちょうどに通知したい場合は、Androidの設定→アプリ→福井番組表→「アラームとリマインダー」も許可してください

アプリは次回以降、同じ手順で新しい APK を上書きインストールできます（登録した通知は残ります）。

---

## 使い方のメモ

- 番組データのURLはビルド時に自動で入ります。変えたいときはアプリの「通知」タブ→設定→「番組データのURL」
- アプリは3時間ごとに裏で番組表を取り直し、通知の予約も更新します。放送時間が変わった番組にも追従します
- 検索画面の「〜を含む番組を通知」で、キーワード（例：ブローウィンズ）に一致する番組を自動で通知します

## チャンネルを追加する

`fetcher/channels.json` に1行足して `enabled` を `true` にします。

- NHKのサービス：`"source": "nhk", "service": "s5"` のように指定
- 自分で用意した番組データ：`"source": "json_url", "url": "https://…/{date}.json"`
  - `{date}` は `2026-09-24` の形に置き換わります
  - 返す JSON は `{"programs":[{"start":"2026-09-24T19:00:00+09:00","end":"…","title":"…","desc":"…","genre":"…"}]}`

NHKの地域コードは同じファイルの `nhk.area`（`180` = 福井）です。

## うまくいかないとき

| 症状 | 確認すること |
| --- | --- |
| 「番組表を更新」が赤くなる | `NHK_API_KEY` が登録されているか。ログに `401` や `403` ならキーの誤りです。`想定外のNHK APIレスポンス形式` ならAPIの仕様変更なので、そのログを Claude に見せてください |
| 「アプリをビルド」が赤くなる | ログの最後のエラーを Claude に見せてください |
| アプリに「番組データがまだありません」 | 手順6のURLがブラウザで開けるか確認し、アプリの「今すぐ更新」を押す |
| しばらくして番組表が更新されなくなった | GitHub は60日間動きのないリポジトリの定期実行を止めることがあります。「Actions」→「番組表を更新」で「Enable workflow」を押してください |

## 注意

- 個人利用を前提にしています。番組データを取得元の利用規約の範囲で使ってください
- 番組情報の出典：NHK番組表API（NHK）
- このアプリは福井ケーブルテレビ・NHK・各放送局の公式アプリではありません
- APK の署名には、このリポジトリに入っている個人用の固定鍵（`app/debug.keystore`）を使っています。Google Play で公開する場合は、専用の署名鍵を作り直してください
