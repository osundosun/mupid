# Runs one run_queue.py batch against Flow Music, then syncs
# downloads/{audio,covers,metadata} to the GPU Jupyter box (~/flow_music_data)
# over WSL+rsync -- only if the batch actually succeeded.
#
# Usage:
#   .\scripts\run_batch_and_sync.ps1              # limit 100 (default)
#   .\scripts\run_batch_and_sync.ps1 -Limit 50     # custom batch size

param(
    [int]$Limit = 100
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

Write-Host "=== run_queue.py --limit $Limit --confirm-run ===" -ForegroundColor Cyan
& .venv\Scripts\python.exe src\run_queue.py --limit $Limit --confirm-run
$queueExit = $LASTEXITCODE

if ($queueExit -ne 0) {
    Write-Host "=== run_queue.py exited with code $queueExit (stopped early / failure). Syncing what completed so far anyway. ===" -ForegroundColor Yellow
} else {
    Write-Host "=== run_queue.py completed cleanly. ===" -ForegroundColor Green
}

Write-Host "=== syncing downloads/ to GPU (flow_music_data) ===" -ForegroundColor Cyan
wsl bash /mnt/c/Users/SSAFY/flow-music-automation/scripts/sync_to_gpu.sh

exit $queueExit
