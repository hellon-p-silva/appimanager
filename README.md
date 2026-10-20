# Gerenciador de Arquivos (AppiManager)

Aplicativo Android nativo (Java, sem dependências pesadas — APK de ~90 KB) para navegar e gerenciar arquivos do armazenamento interno.

## Recursos

- Navegação por pastas com barra de caminho clicável e botão Voltar
- Ícones por tipo (pasta, imagem, vídeo, áudio, documento, compactado, APK)
- Abrir arquivos com o aplicativo adequado ("Abrir com…" quando não houver padrão)
- Seleção múltipla (toque longo): copiar, mover, excluir, compartilhar, renomear, propriedades, selecionar tudo
- Colar com resolução automática de nomes repetidos (`arquivo (1).txt`)
- Criar nova pasta / novo arquivo
- Pesquisa recursiva a partir da pasta atual
- Ordenação por nome, data, tamanho ou tipo (crescente/decrescente), pastas sempre primeiro
- Mostrar/ocultar arquivos ocultos
- Espaço livre/total do armazenamento
- Tema claro e escuro automáticos

Requer Android 8.0 (API 26) ou superior. No Android 11+ o app pede a permissão
"Acesso a todos os arquivos" na primeira execução.

## Compilar

```bash
echo "sdk.dir=/caminho/para/android-sdk" > local.properties
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

O build `release` é assinado com a chave de debug para poder ser instalado direto.
Para publicar na Play Store, configure sua própria chave de assinatura em `app/build.gradle`.

O workflow `.github/workflows/build.yml` gera o APK automaticamente a cada push
(disponível em *Actions → Build APK → Artifacts*).
