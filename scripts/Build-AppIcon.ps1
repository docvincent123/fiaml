param([string]$OutputPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'desktop/QureMed.Desktop/Assets/RehaFlow.ico'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$bitmap = New-Object Drawing.Bitmap 256,256
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$mint = [Drawing.ColorTranslator]::FromHtml('#79E1C0')
$pen = New-Object Drawing.Pen $mint,24
$pulse = New-Object Drawing.Pen ([Drawing.Color]::White),7
$stream = New-Object IO.MemoryStream
try {
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([Drawing.ColorTranslator]::FromHtml('#0B1420'))
    $graphics.DrawEllipse($pen,51,45,151,151)
    $pen.StartCap = $pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
    $graphics.DrawLine($pen,170,179,206,216)
    $pulse.LineJoin = [Drawing.Drawing2D.LineJoin]::Round
    $points = [Drawing.Point[]]@((New-Object Drawing.Point 80,131),(New-Object Drawing.Point 108,131),(New-Object Drawing.Point 119,105),(New-Object Drawing.Point 136,152),(New-Object Drawing.Point 151,126),(New-Object Drawing.Point 174,126))
    $graphics.DrawLines($pulse,$points)
    $bitmap.Save($stream,[Drawing.Imaging.ImageFormat]::Png)
    $png=$stream.ToArray()
    [IO.Directory]::CreateDirectory((Split-Path $OutputPath -Parent)) | Out-Null
    $file=[IO.File]::Create($OutputPath)
    $writer=New-Object IO.BinaryWriter $file
    try {
        $writer.Write([uint16]0);$writer.Write([uint16]1);$writer.Write([uint16]1)
        $writer.Write([byte]0);$writer.Write([byte]0);$writer.Write([byte]0);$writer.Write([byte]0)
        $writer.Write([uint16]1);$writer.Write([uint16]32)
        $writer.Write([uint32]$png.Length);$writer.Write([uint32]22);$writer.Write($png)
    } finally {$writer.Dispose();$file.Dispose()}
} finally {$stream.Dispose();$pulse.Dispose();$pen.Dispose();$graphics.Dispose();$bitmap.Dispose()}
