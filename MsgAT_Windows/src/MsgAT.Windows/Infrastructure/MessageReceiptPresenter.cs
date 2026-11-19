// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Animation;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Infrastructure;

public sealed class MessageReceiptPresenter : ContentPresenter
{
    private static readonly DependencyProperty ObservedReceiptProperty = DependencyProperty.Register(
        "ObservedReceipt", typeof(MessageReceiptPresentation), typeof(MessageReceiptPresenter),
        new PropertyMetadata(new MessageReceiptPresentation(4), ReceiptChanged));

    private readonly ScaleTransform _scale = new();

    public MessageReceiptPresenter()
    {
        RenderTransform = _scale;
        RenderTransformOrigin = new Point(0.5, 0.5);
        SetBinding(ObservedReceiptProperty, new Binding(nameof(Content)) { Source = this, Mode = BindingMode.OneWay });
        Unloaded += (_, _) => StopTransition();
    }

    private static void ReceiptChanged(DependencyObject sender, DependencyPropertyChangedEventArgs args)
    {
        var presenter = (MessageReceiptPresenter)sender;
        var previous = (MessageReceiptPresentation)args.OldValue;
        var current = (MessageReceiptPresentation)args.NewValue;

        var sameMessage = !string.IsNullOrEmpty(current.MessageId) &&
            string.Equals(current.MessageId, previous.MessageId, StringComparison.Ordinal);
        if (sameMessage && previous.ReceiptStatus == current.ReceiptStatus) return;
        presenter.StopTransition();
        if (!sameMessage || !presenter.IsLoaded || !presenter.IsVisible || !SystemParameters.ClientAreaAnimation ||
            previous.ReceiptStatus is < -1 or > 3 || current.ReceiptStatus is < -1 or > 3) return;

        presenter.BeginAnimation(OpacityProperty, Reveal(0.42));
        presenter._scale.BeginAnimation(ScaleTransform.ScaleXProperty, Reveal(0.78));
        presenter._scale.BeginAnimation(ScaleTransform.ScaleYProperty, Reveal(0.78));
    }

    private static DoubleAnimation Reveal(double start) => new(start, 1, TimeSpan.FromMilliseconds(160))
    {
        FillBehavior = FillBehavior.Stop,
        EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
    };

    private void StopTransition()
    {
        BeginAnimation(OpacityProperty, null);
        _scale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
        _scale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
    }
}
