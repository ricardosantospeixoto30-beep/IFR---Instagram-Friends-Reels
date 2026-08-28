# Friends Reels Inbox

App Android que reúne, num feed vertical, os Reels que amigos enviaram em DMs do Instagram.

Ver **`Friends_Reels_Inbox_Technical_Spec_v2.md`** para a spec completa e **`PROJECT_PROGRESS.md`** para o estado atual do desenvolvimento, decisões e próximos passos.

## Como abrir

1. Clonar o repo.
2. Abrir a pasta no **Android Studio** (File → Open…).
3. Aguardar a sincronização Gradle (primeira vez demora — descarrega Gradle 8.10.2 e dependências).
4. Correr no dispositivo (USB debugging ligado).

## Requisitos de build

- Android Studio Ladybug (2024.2) ou mais recente.
- JDK 17+ (o AS trás o próprio JBR).
- Android SDK 35.

## Ferramentas de dump / long-press (PoC-2/PoC-3)

Quando o `AccessibilityService` está ativo, é possível pedir um dump da árvore de acessibilidade do ecrã atual ou disparar um long-press automático no primeiro Reel visível da conversa aberta.

```bash
# Dump da árvore de acessibilidade do ecrã atual.
adb shell am broadcast -a com.example.friendsreels.ACTION_DUMP_TREE

# Long-press no primeiro Reel da conversa aberta + dump automático 1.5s depois.
# Requer estar dentro de uma conversa do Instagram que contenha um Reel visível.
adb shell am broadcast -a com.example.friendsreels.ACTION_LONG_PRESS_FIRST_REEL
```

Ver o resultado em `adb logcat -s IGReaderService` (ou no Logcat do Android Studio com filtro `IGReaderService`).

Todos os dumps de referência estão em `docs/screen-dumps/`.
