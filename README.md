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
2. Puxar a barra de notificações → tocar **🔍** (só Reels visíveis) OU abrir a app e tocar **"📥 Descobrir histórico (scroll auto)"** (percorre a conversa para trás até 30 scrolls). A app persiste em BD tudo o que encontrar.
3. Para cada Reel que queres enriquecer com URL, ir até ele estar no topo e tocar **🔗** na notificação.
4. Abrir a app → **"▶ Abrir o meu feed"**. Feed vertical full-screen: swipe up/down entre Reels. Cada card mostra chips com o estado (`recebido/enviado`, `visto`, `❤/😂 reagido`, `💬 respondido`), autor, remetente na DM, data, e botão central para ver o Reel (embed com autoplay). Menu **⋮** com "Abrir Reel/conversa no Instagram nativo", "Cancelar pendentes", "Definições".
5. Tocar em **❤ / 😂** → enfileira a reacção nesse Reel. Tocar em **💬** → abre dialog de resposta com texto configurável. Toast confirma "Ação enfileirada".
6. Voltar ao Instagram (opcional — o batching navega entre conversas E procura o Reel específico dentro da thread via scroll), tocar **▶** na notificação. O executor agrupa a fila por conversa, visita cada thread uma vez, e dentro dela scrolla para trás até encontrar o Reel exacto antes de reagir/responder.

## Definições (⚙)

- **Ignorar Reels enviados por mim** — quando ligado, as reacções e respostas só se aplicam a Reels que amigos enviaram.
- **Inverter direção do swipe** — swipe UP passa a voltar ao Reel anterior (padrão: swipe UP = próximo Reel).
- **Seleção de conversas** — placeholder, próxima iteração.
- **Ferramentas de diagnóstico** — broadcasts directos para debug (equivalentes a `adb shell am broadcast`).

## Broadcasts (opcional — testes via `adb`)

Todas as acções são disparáveis por broadcast:

```bash
adb shell am broadcast -a com.example.friendsreels.ACTION_DISCOVER_REELS
adb shell am broadcast -a com.example.friendsreels.ACTION_DISCOVER_REELS_HISTORY
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

Resumo: PoCs 1→7 concluídos. PoC-8 iterações 1-3 (batching, history-scroll, player embed) e iter 4 (localizar Reel por autor + scroll) fechadas. PoC-9 iter 1 (nav cross-thread) validada em device. **Sessão 35 (iteração grande)** entregou o feed VerticalPager full-screen (spec §3), reply real com texto (§5), estados por Reel (§7), menu 3-pontinhos (§12), ecrã Definições (§11), Home limpa. Aguarda validação end-to-end — instruções passo-a-passo em `PROJECT_PROGRESS.md` §8.
