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

Se precisares dos primitivos directos (reagir com ❤/😂, responder com 👀, tudo aplicado ao 1.º Reel recebido visível), abre a app **Friends Reels** — os botões estão no ecrã principal.

## Fluxo recomendado (batching de acções — desde a sessão 26)

1. Abrir uma conversa com Reels no Instagram.
2. Puxar a barra de notificações → tocar **🔍**. A app persiste em BD todos os Reels visíveis.
3. Para cada Reel que queres enriquecer com URL, ir até ele estar no topo e tocar **🔗**.
4. Abrir o feed (tocar no corpo da notif, ou botão "Ver feed" na app).
5. Em cada card, tocar **Enfileirar ❤ / 😂 / 👀** conforme quiseres. Reacções e reply são dedup por card. Botão **✕ Cancelar acções pendentes deste Reel** limpa os pendentes desse card.
6. Voltar ao Instagram, **certificar-se de que a conversa correcta está aberta**, tocar **▶** na notificação. O executor corre a fila em série. Rows para outras conversas ficam `FAILED` até a navegação por thread_id existir (PoC-9).

## Broadcasts (opcional — testes via `adb`)

Todas as acções são disparáveis por broadcast:

```bash
adb shell am broadcast -a com.example.friendsreels.ACTION_DISCOVER_REELS
adb shell am broadcast -a com.example.friendsreels.ACTION_COPY_REEL_URL
adb shell am broadcast -a com.example.friendsreels.ACTION_APPLY_PENDING
adb shell am broadcast -a com.example.friendsreels.ACTION_REACT_HEART
adb shell am broadcast -a com.example.friendsreels.ACTION_REACT_LAUGH
adb shell am broadcast -a com.example.friendsreels.ACTION_REPLY_FIRST_REEL_MOCK
```

Ver o resultado em `adb logcat -s IGReaderService` (ou no Logcat do Android Studio com filtro `IGReaderService`). A tag `build=sNN` no arranque confirma qual APK está em execução.

Todos os dumps de referência estão em `docs/screen-dumps/`.

## Estado atual

Ver `PROJECT_PROGRESS.md` — secção **"Estado atual"** e o último log de sessão.

Resumo: PoCs 1→7 concluídos e validados no OnePlus Nord 5. PoC-8 iter 1 e 2 fechados. PoC-8 iter 3 parte A (batching) implementada; próximos passos são a parte B (scroll auto para descoberta em lote) e a parte C (player embutido).
