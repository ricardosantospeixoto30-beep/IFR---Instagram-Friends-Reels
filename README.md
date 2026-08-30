# Friends Reels Inbox

App Android que reúne, num feed vertical, os Reels que amigos enviaram em DMs do Instagram — sem modificar o Instagram, sem root, sem risco de ban.

Ver **`Friends_Reels_Inbox_Technical_Spec_v2.md`** para a spec completa e **`PROJECT_PROGRESS.md`** para o estado atual, decisões e próximos passos.

## Arquitetura em 30s

App externa Android + `AccessibilityService` (Opção C da spec). O serviço navega/inspeciona o Instagram oficial via a11y e dispatch de gestos, e a nossa app armazena os Reels descobertos numa BD local Room.

## Como abrir

1. Clonar o repo.
2. Abrir a pasta no **Android Studio** (File → Open…).
3. Aguardar a sincronização Gradle (primeira vez demora — descarrega Gradle 8.10.2 e dependências).
4. Correr no dispositivo (USB debugging ligado).
5. Ao abrir a app pela primeira vez, aceitar o pedido de permissão de notificações.
6. Ativar o serviço de acessibilidade **Friends Reels — Instagram Reader** (botão na app ou Definições → Acessibilidade).

## Requisitos de build

- Android Studio Ladybug (2024.2) ou mais recente.
- JDK 17+ (o AS trás o próprio JBR).
- Android SDK 35.
- Dispositivo com Android 13+ (validado em OnePlus Nord 5 / Android 16).

## Notificação persistente

Depois de o a11y service estar activo, aparece uma notificação persistente **"Friends Reels"** com 3 botões:

| Botão | Acção |
|---|---|
| 🔍 | Descobrir os Reels visíveis na conversa aberta (adiciona à BD sem URL). |
| 🔗 | Copiar o URL do 1.º Reel recebido visível (abre viewer → Partilhar → Copiar ligação → volta para a conversa). Enriquece a row na BD com URL e `dmSender`. |
| ▶ | Aplicar a fila de acções pendentes (ver "Batching de acções" abaixo). |

Tocar **no corpo** da notificação abre o feed local.

Se precisares dos primitivos directos (reagir com ❤/😂, responder com 👀, tudo aplicado ao 1.º Reel recebido visível), ou de descobrir todo o histórico de uma conversa via scroll automático, abre a app **Friends Reels** → **⚙ Definições** → secção "Ferramentas de diagnóstico".

## Fluxo recomendado

1. Abrir uma conversa com Reels no Instagram.
2. Puxar a barra de notificações → tocar **🔍** (só Reels visíveis) OU abrir a app e tocar **"📥 Descobrir histórico (scroll auto)"**. A app persiste em BD tudo o que encontrar.
3. Abrir a app → **"▶ Abrir o meu feed"**. Feed vertical full-screen com auto-play (WebView embed por página). Se algum Reel ainda não tem URL, aparece um placeholder com **"🔗 Preparar Reel"** — tocar dispara enrichment automático (IG abre, encontra o Reel, copia o URL, volta) e o vídeo passa a fazer auto-play.
4. Swipe up/down entre Reels. Chips mostram estado (`recebido/enviado`, `visto`, reacção actual, `respondido`). Menu **⋮**: "Abrir Reel no Instagram nativo", "Cancelar pendentes", "Definições".
5. Tocar em **❤ / 😂** → enfileira a reacção (IG só permite uma reacção por mensagem; se enfileirares outra, a antiga é substituída). Tocar em **💬** → dialog editável para escrever a resposta.
6. Baixar notif → **▶ Aplicar fila**. O executor agrupa por conversa, navega **sozinho** (mesmo partindo da Home do IG), scrolla para trás se preciso e aplica reacção/resposta ao Reel correcto.

## Definições (⚙)

- **Ignorar Reels enviados por mim** — reacções/respostas só afectam Reels que amigos enviaram.
- **Filtrar conversas no feed** (spec §8): 3 modos —
  - **Ver tudo** (default): sem filtro.
  - **Apenas as selecionadas**: feed só mostra Reels das threads escolhidas.
  - **Todas EXCEPTO as selecionadas**: feed esconde Reels das threads escolhidas.
  A lista de threads descobertas aparece com checkboxes e contagem de Reels por thread.
- **Ferramentas de diagnóstico** — broadcasts directos para debug.

## Broadcasts (opcional — testes via `adb`)

Todas as acções são disparáveis por broadcast:

```bash
adb shell am broadcast -a com.example.friendsreels.ACTION_DISCOVER_REELS
adb shell am broadcast -a com.example.friendsreels.ACTION_DISCOVER_REELS_HISTORY
adb shell am broadcast -a com.example.friendsreels.ACTION_COPY_REEL_URL
adb shell am broadcast -a com.example.friendsreels.ACTION_APPLY_PENDING
adb shell am broadcast -a com.example.friendsreels.ACTION_ENRICH_REEL_URL --el reel_id 1
adb shell am broadcast -a com.example.friendsreels.ACTION_REACT_HEART
adb shell am broadcast -a com.example.friendsreels.ACTION_REACT_LAUGH
adb shell am broadcast -a com.example.friendsreels.ACTION_REPLY_FIRST_REEL_MOCK
```

Ver o resultado em `adb logcat -s IGReaderService` (ou no Logcat do Android Studio com filtro `IGReaderService`). A tag `build=sNN` no arranque confirma qual APK está em execução.

Todos os dumps de referência estão em `docs/screen-dumps/`.

## Estado atual

Ver `PROJECT_PROGRESS.md` — secção **"Estado atual"** e o último log de sessão. Log histórico das sessões 1-32 em `docs/session-log-archive.md`.

**Resumo (fim s37):** MVP tem todos os pilares da spec cobertos e validados em device — feed vertical com auto-play (§3), reagir (§4), responder com texto real (§5), estados por Reel (§7), seleção de conversas (§8), menu 3-pontinhos (§12), definições (§11), descoberta manual + histórico (§9 da spec), enrichment automático de URL. Próxima sessão arranca da §6.1 do PROJECT_PROGRESS.
