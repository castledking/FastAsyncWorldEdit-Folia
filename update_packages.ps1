$baseDir = "c:\Users\Towki\Repositories\FastAsyncWorldEdit\worldedit-bukkit\adapters\adapter-1_21_8\src\main\java\com\sk89q\worldedit\bukkit\adapter\impl\fawe\v1_21_8"

# Get all Java files recursively
$javaFiles = Get-ChildItem -Path $baseDir -Recurse -Filter "*.java"

foreach ($file in $javaFiles) {
    $content = Get-Content -Path $file.FullName -Raw
    $updatedContent = $content -replace 'com\.sk89q\.worldedit\.bukkit\.adapter\.impl\.fawe\.v1_21_6', 'com.sk89q.worldedit.bukkit.adapter.impl.fawe.v1_21_8'
    
    if ($updatedContent -ne $content) {
        Set-Content -Path $file.FullName -Value $updatedContent -NoNewline
        Write-Host "Updated: $($file.FullName)"
    } else {
        Write-Host "No changes needed: $($file.FullName)"
    }
}
