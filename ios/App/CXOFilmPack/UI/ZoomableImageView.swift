import SwiftUI
import UIKit

/// Pinch / pan / double-tap zoom. Press and hold anywhere reports `onHold(true)` until release (compare with original).
struct ZoomableImageView: UIViewRepresentable {
    let image: UIImage?
    let token: Int
    let onHold: (Bool) -> Void

    func makeUIView(context: Context) -> ZoomScrollView {
        let view = ZoomScrollView()
        view.onHold = onHold
        return view
    }

    func updateUIView(_ view: ZoomScrollView, context: Context) {
        view.onHold = onHold
        if view.token != token { view.token = token; view.zoomScale = 1 }
        if view.imageView.image !== image { view.imageView.image = image }
    }
}

final class ZoomScrollView: UIScrollView, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    let imageView = UIImageView()
    var token = -1
    var onHold: ((Bool) -> Void)?
    private var lastBounds = CGSize.zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        minimumZoomScale = 1
        maximumZoomScale = 8
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        bouncesZoom = true
        backgroundColor = .clear
        imageView.contentMode = .scaleAspectFit
        imageView.isUserInteractionEnabled = true
        imageView.layer.minificationFilter = .trilinear
        addSubview(imageView)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)

        let hold = UILongPressGestureRecognizer(target: self, action: #selector(handleHold(_:)))
        hold.minimumPressDuration = 0.25
        hold.allowableMovement = 1000
        hold.cancelsTouchesInView = false
        hold.delegate = self
        addGestureRecognizer(hold)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastBounds {
            lastBounds = bounds.size
            zoomScale = 1
            imageView.frame = bounds
            contentSize = bounds.size
        }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    @objc private func handleDoubleTap(_ g: UITapGestureRecognizer) {
        if zoomScale > 1.01 {
            setZoomScale(1, animated: true)
        } else {
            let point = g.location(in: imageView)
            let size = CGSize(width: bounds.width / 3, height: bounds.height / 3)
            zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: true)
        }
    }

    @objc private func handleHold(_ g: UILongPressGestureRecognizer) {
        switch g.state {
        case .began: onHold?(true)
        case .ended, .cancelled, .failed: onHold?(false)
        default: break
        }
    }
}
