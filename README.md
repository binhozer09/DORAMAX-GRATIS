# DORAMAX iOS (versão web/PWA)

Projeto independente do app Android, publicado na branch `doramax-ios`.

## Publicação
No GitHub, abra Settings → Pages e selecione:
- Source: Deploy from a branch
- Branch: `doramax-ios`
- Folder: `/ (root)`

O endereço será exibido pelo GitHub Pages após a ativação.

## iPhone
Abra o endereço no Safari → Compartilhar → Adicionar à Tela de Início.

## Backend separado
Supabase: `https://maigqgxtlecxmkeqmjxz.supabase.co`
Projeto: `maigqgxtlecxmkeqmjxz`

O catálogo público de vídeos e a configuração pública foram copiados para o banco iOS. Usuários, sessões e permissões são independentes do Android. A conta administrativa precisa ser criada e autorizada separadamente no projeto iOS.

## Observações
- Atualizações do site são publicadas pela hospedagem e carregadas pelo navegador.
- A instalação PWA não usa APK nem App Store.
- A reprodução usa YouTube; no iPhone o início do vídeo pode exigir toque por restrições de autoplay do iOS.
- O SDK nativo de anúncios AdMob do Android não roda em um site; anúncios web precisam de configuração própria.
