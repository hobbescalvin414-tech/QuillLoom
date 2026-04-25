param(
    [string]$BookFile = "1.txt"
)

$ErrorActionPreference = "Stop"

$timestamp = Get-Date -Format "yyyyMMddHHmmss"
$projectId = "book-draft-$timestamp"

Write-Host "[book-draft-smoke] projectId=$projectId"
Write-Host "[book-draft-smoke] bookFile=$BookFile"

mvn -q "-Dtest=BookWorkflowSmokeTest" `
  "-Dquillloom.test.book-workflow.enabled=true" `
  "-Dspring.profiles.active=dev" `
  "-Dquillloom.test.book-workflow.file=$BookFile" `
  "-Dquillloom.test.book-workflow.project-id=$projectId" `
  test

Write-Host "[book-draft-smoke] completed projectId=$projectId"
Write-Host "[book-draft-smoke] next: .\scripts\review-create-baseline.ps1 -ProjectId $projectId"
Write-Host "[book-draft-smoke] next: .\scripts\review-start.ps1 -ProjectId $projectId"
