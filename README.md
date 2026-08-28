# Friends Reels Inbox

App Android que reúne, num feed vertical, os Reels que amigos enviaram em DMs do Instagram.

Ver **`Friends_Reels_Inbox_Technical_Spec_v2.md`** para a spec completa e **`PROJECT_PROGRESS.md`** para o estado atual do desenvolvimento, decisões e próximos passos.

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

## Como testar as ações PoC no telemóvel (fluxo recomendado)

Depois do serviço de acessibilidade estar ativo, aparece uma **notificação persistente "Friends Reels"** com botões `❤`, `😂` e `Dump`.

1. Abrir o Instagram e entrar numa conversa com Reels visíveis.
2. Baixar a barra de notificações.
3. Tocar em `❤` ou `😂` na notificação Friends Reels.
4. O shade fecha, o IG mantém-se na conversa e a reação é aplicada ao Reel mais próximo do topo do ecrã.

Este fluxo evita a troca de foreground (que causava o IG a voltar ao inbox em versões anteriores).

## Ferramentas de dump / long-press via adb (opcional)

Todas as ações também podem ser disparadas por broadcast e há botões equivalentes na `MainActivity` para debug.

```bash
# Dump da árvore de acessibilidade da janela ativa.
adb shell am broadcast -a com.example.friendsreels.ACTION_DUMP_TREE

# Dump de TODAS as janelas atualmente visíveis (inclui popups, dialogs,
# bottom sheets, keyboards, overlays). Necessário para inspecionar menus
# de contexto que o Android coloca numa janela separada.
adb shell am broadcast -a com.example.friendsreels.ACTION_DUMP_ALL_WINDOWS

# Long-press no primeiro Reel da conversa aberta + dump de todas as janelas
# 2.1 s depois. Requer estar dentro de uma conversa com um Reel visível.
adb shell am broadcast -a com.example.friendsreels.ACTION_LONG_PRESS_FIRST_REEL

# Reagir com ❤ ou 😂 ao primeiro Reel da conversa aberta.
adb shell am broadcast -a com.example.friendsreels.ACTION_REACT_HEART
adb shell am broadcast -a com.example.friendsreels.ACTION_REACT_LAUGH
```

Ver o resultado em `adb logcat -s IGReaderService` (ou no Logcat do Android Studio com filtro `IGReaderService`).

Todos os dumps de referência estão em `docs/screen-dumps/`.

## Estado atual

Ver `PROJECT_PROGRESS.md` — secção **"Estado atual"** e o último log de sessão. Resumo curto: PoCs 1, 2, 3 e 5 concluídos e verificados no OnePlus Nord 5. Próximo passo: **PoC-4** (identificar remetente).
