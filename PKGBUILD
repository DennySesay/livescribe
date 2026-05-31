# Maintainer: Denny Sesay <denny@example.com>
pkgname=livescribe-bin
_pkgname=livescribe
pkgver=1.0.0
pkgrel=1
pkgdesc="A Java CLI tool that monitors streaming channels and automatically starts recording when a streamer goes live"
arch=('x86_64')
url="https://github.com/DennySesay/livescribe"
license=('MIT')
depends=('streamlink' 'ffmpeg')
provides=('livescribe')
conflicts=('livescribe')
source=("https://github.com/DennySesay/livescribe/releases/download/v${pkgver}/livescribe-linux")
sha256sums=('SKIP')

package() {
    # Install the compiled native binary
    install -Dm755 "livescribe-linux" "${pkgdir}/usr/bin/livescribe"

    # Install the license file
    install -Dm644 "LICENSE" "${pkgdir}/usr/share/licenses/${pkgname}/LICENSE"
}
