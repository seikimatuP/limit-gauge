# Limit Gauge — Claude Code のリミットを Android ウィジェットに

ChatGPT アプリの Codex ウィジェット（5時間・週間の残り）の Claude 版です（非公式）。
Galaxy などの Android ホーム画面に Claude Code の **5時間リミット / 週間リミット** の残りを表示します。

```
PC: Claude Code ──(ステータスラインの rate_limits)──> limit-gauge-push.mjs
                                                        │ HTTPS + トークン
                                                        ▼
                              Cloudflare Worker（あなたのアカウント、無料プラン）
                                                        │ 15〜30分ごとに取得
                                                        ▼
                                         Android: リミットゲージ（ウィジェット）
```

- データ元は Claude Code が公式にステータスラインへ渡している `rate_limits`（5時間・7日の使用率とリセット時刻）だけです。
  Claude の OAuth トークンやログイン情報は一切読みません（Anthropic の規約上、Claude のログイン情報を他アプリで使うことは禁止されているため）。
- Pro / Max プランで Claude Code を使っている場合に値が出ます。

## 中身

| 場所 | 内容 |
|---|---|
| [Releases](https://github.com/seikimatuP/limit-gauge/releases/latest) | ビルド済み APK（minSdk 31 / targetSdk 36、署名済み） |
| `android/` | アプリのソース（Java、AndroidX 不使用）。Android Studio でそのまま開けます |
| `relay/` | 中継用 Cloudflare Worker（Durable Object/SQLite）と `npm run setup` |
| `pc/limit-gauge-push.mjs` | Claude Code のステータスラインに挟む送信スクリプト（Node 18+、依存なし） |

## セットアップ（初回 10 分ほど）

### 1. スマホにアプリを入れる
[Releases](https://github.com/seikimatuP/limit-gauge/releases/latest) から `LimitGauge-<バージョン>.apk` をスマホでダウンロードして開き、インストールします。
- 「提供元不明のアプリ」の許可を求められたら、開いたアプリ（マイファイル／ブラウザ等）に許可してください。
- Galaxy で **自動ブロッカー（Auto Blocker）** がオンだとインストールできません。設定 → セキュリティとプライバシー → 自動ブロッカー を一時的にオフにしてください。

### 2. PC でリレーをデプロイ（WSL で OK、Node 22 以上）
```bash
cd relay
npm install
npm run setup
```
`setup` が次を順に行います。
1. Cloudflare ログイン（未ログインならブラウザが開きます）
2. `limit-gauge-relay` Worker をデプロイ
3. ランダムなトークンを 2 つ作って Worker のシークレットに設定（`relay/.relay.json` に保存）
   - `TOKEN`: PC 用（書き込みと読み取り）
   - `TOKEN_READ`: スマホ用（読み取りのみ。スマホから漏れても値の書き換えはできません）
4. ペアリングページ（QR コード）をブラウザで開く
5. この環境の `~/.claude/settings.json` に送信処理を追加（確認あり）

既存のステータスライン（ccstatusline など）は**そのまま表示されます**。送信処理はその前段に挟まるだけです。

### 3. スマホとペアリング
PC に表示された QR コードを Galaxy のカメラで読み取り →「アプリで開く」→「保存」。
（開かない場合は、ページ下の「手動で入力する」の URL とトークンをアプリに入力）

### 4. ウィジェットを置く
アプリの「ウィジェット」欄のボタン、またはホーム画面の長押し → ウィジェット →「リミットゲージ」から。
- **カード（2×2）**: 週間（大きいリング）と5時間（小さいリング）の両方。横に広げると横並びレイアウトになります
- **週間リング / 5時間リング（1×1）**: リングの中に残り%。2×1 のように横長に広げると「リング＋3行（ラベル・残り%・リセットまで）」、2×2 以上では大きいリングの下に3行を出します

どのウィジェットも、細いリングのゲージと「Claude · Weekly ／ 94% left ／ Resets in 6 days」（日本語表示では「Claude · 週間 ／ 残り 94% ／ あと6日でリセット」）の3行で、青系の単色トーンにそろえています。
ロック画面に置ける端末では、ロック画面に置いたときだけ背景の板を消して表示します。

- **ロック画面用（背景なし）（4×1）**: 週間と5時間を横に並べ、それぞれ「リング＋3行（ラベル・残り%・リセットまで）」で出します。置き場所に関係なく背景は常に透明です。壁紙の明暗に左右されないよう、白い文字と淡い青のリングに暗い影を付けた固定色で描きます（残り 20% 未満は赤）。幅が狭いときはラベル行を省いた縮小版に切り替わります

#### Galaxy のロック画面に背景なしで置く（Good Lock の LockStar）
Galaxy では、常時表示の通知は枠（カード）付きで出ます。枠なしで出したいときは、Good Lock の LockStar でロック画面にウィジェットを置きます。
1. Galaxy Store から **Good Lock** を入れ、その中の **LockStar** を追加して起動する
2. LockStar をオンにし、ロック画面の編集画面を開く
3. 「アプリウィジェット」（ウィジェットの追加）から「リミットゲージ」→「ロック画面用（背景なし）」を選んで置き、大きさを調整して保存する

※ Good Lock・LockStar は国や地域、端末、One UI のバージョンによって Galaxy Store に出ないことがあり、日本では入手できない場合があります。画面の名前も One UI のバージョンで変わることがあります。

Claude Code で 1 回何か応答させれば値が入ります。

### Windows 側の Claude Code も使う場合
WSL と Windows の両方で Claude Code を使っているなら、Windows の PowerShell で `pc` フォルダに移動して、setup の最後に表示されるコマンドを実行します:
```powershell
node limit-gauge-push.mjs install --url https://limit-gauge-relay.<あなた>.workers.dev --token-stdin
```
「トークンを貼り付けて」と聞かれたら、`relay/.relay.json` の `token` の値を貼り付けます。
トークンをコマンドラインに直接書くと、PowerShell の履歴やプロセス一覧に残るので避けてください。

## 使い方メモ
- 表示は「残り」（Codex と同じ）。アプリの設定で「使用済み」に切り替え可能。残り 20% 未満でリングが赤くなります。
- 「通知とロック画面に常時表示」をオンにすると、ロック画面にも出ます（Galaxy はロック画面の通知表示を「詳細」にしておくと見やすい）。
  - 通知はウィジェットと同じリングのデザインです。たたんだ状態は週間のリング・残り%・リセットまでと、小さな5時間のリング。広げると週間と5時間のリングが横に並びます。
  - 色は端末のライト／ダーク設定に合わせて切り替わります。
- Android 16 では「Live Update として表示」もオンにすると、許可された場合ステータスバーのチップにも表示されます（端末側の対応次第）。
  - Live Update をオンにしている間は、通知は Android 標準の見た目（タイトル＋進捗バー）になります。リングのデザイン（カスタムビュー）を使うと Live Update に昇格できないためです。
- **反映タイミング**: Claude Code が応答したとき PC から送信 → アプリは 15〜30 分ごと＋アプリを開いたときに取得。
  claude.ai やモバイルアプリだけで使った分は、次に Claude Code が応答したときに反映されます。
- リセット時刻を過ぎると自動で「リセット済み」になります（最大 10 分ほど遅れることがあります）。
- 更新が遅い場合: 設定 → バッテリー → バックグラウンドでの使用制限 で、リミットゲージを「スリープしないアプリ」に入れてください。

## コマンド（PC 側）
```bash
node ~/.claude/limit-gauge-push.mjs status       # 設定と、リレーが持っている値を表示
node ~/.claude/limit-gauge-push.mjs uninstall    # ステータスラインを元に戻す（--purge で設定も削除）
cd relay && npm run setup -- --new-token          # 2 つのトークンを作り直す（スマホと各 PC を再設定）
cd relay && npx wrangler delete                   # リレーを削除
```
うまく送られないときは `LIMIT_GAUGE_DEBUG=1` を付けて Claude Code を起動すると `~/.claude/limit-gauge.log` にログが出ます。
プロジェクトの `.claude/settings.json` に別の `statusLine` があると、そちらが優先されて送信されません。

## リレーの API（自前サーバーに置き換えたい場合）
- `POST /v1/usage` … `{"captured_at":秒,"five_hour":{"used_percentage":23.5,"resets_at":秒},"seven_day":{...}}`（Claude Code のステータスライン JSON をそのまま送っても可）
- `GET /v1/usage` … アプリが読む JSON。どちらも `Authorization: Bearer <TOKEN>`。GET は `TOKEN_READ` でも可（`TOKEN_READ` で POST すると 403）
- アプリの URL 欄にパス付きの URL を入れると、その URL をそのまま GET します（`rate_limits` を含む JSON なら何でも可。Tailscale 経由の自宅サーバーなど）。
- `http://` が使えるのは LAN や Tailscale のアドレス（プライベート IP、`100.64.0.0/10`、`*.ts.net`、`localhost` など）だけです。インターネット上のサーバーは `https://` にしてください（トークンが平文で流れるのを防ぐため）。

## ビルドについて
- Releases の APK は Gradle を使わず `android/tools/build-apk.sh`（aapt2 + javac + AOSP の dx + v2 署名）でビルドし、apksigtool で署名を検証済みです。出力先は `dist/` です。
- Android Studio では `android/` を開けば Gradle（AGP 8.13.2 / Gradle 8.14.3）でビルドできる構成にしてありますが、Gradle ビルド自体はこちらの環境では実行できていません。
- Releases の APK は作者の鍵で署名しています（鍵はリポジトリに含めていません）。自分でビルドした APK は署名が異なるため Releases 版の上から上書きインストールできません。入れ替えるときは一度アンインストールしてください。
- 自分の鍵を使う場合は `android/keystore/` に `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）と鍵ファイルを置きます。**鍵は公開リポジトリに入れないでください**（.gitignore 済み）。
- テスト: `android/tools/run-logic-tests.sh`（解析ロジック）、`cd relay && npm test`（マージロジック）。

## ライセンス
[MIT License](LICENSE)。`relay/src/vendor/qrcode-1.4.4.js.txt` は Kazuhiko Arase 氏の QR Code Generator（MIT License）です。

非公式のツールです。Anthropic とは関係ありません。
