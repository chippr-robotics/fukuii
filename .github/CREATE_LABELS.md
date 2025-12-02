# Creating Agent Labels in GitHub

This guide explains how to create the agent labels in your GitHub repository.

## Creating Labels via GitHub UI

1. Go to your repository on GitHub
2. Click on **Issues** tab
3. Click on **Labels** 
4. Click **New label** button
5. Create each of the following labels:

### Agent Labels to Create

| Label Name | Description | Color | Emoji |
|------------|-------------|-------|-------|
| `agent: wraith 👻` | Compilation errors and Scala 3 migration | `#B60205` (red) | 👻 |
| `agent: mithril ✨` | Code modernization and Scala 3 features | `#FFD700` (gold) | ✨ |
| `agent: ICE 🧊` | Large-scale migrations and strategic planning | `#0E8A16` (green) | 🧊 |
| `agent: eye 👁️` | Testing, validation, and quality assurance | `#1D76DB` (blue) | 👁️ |
| `agent: forge 🔨` | Consensus-critical code (EVM, mining, crypto) | `#D93F0B` (orange) | 🔨 |
| `agent: herald 🧭` | Network protocol and peer communication | `#5319E7` (purple) | 🧭 |
| `agent: Q 🎯` | Process guardian and quality discipline | `#C5DEF5` (light blue) | 🎯 |

## Creating Labels via GitHub CLI

If you have the GitHub CLI (`gh`) installed, you can create all labels at once:

```bash
# Navigate to your repository
cd /path/to/fukuii

# Create agent labels
gh label create "agent: wraith 👻" --description "Compilation errors and Scala 3 migration" --color "B60205"
gh label create "agent: mithril ✨" --description "Code modernization and Scala 3 features" --color "FFD700"
gh label create "agent: ICE 🧊" --description "Large-scale migrations and strategic planning" --color "0E8A16"
gh label create "agent: eye 👁️" --description "Testing, validation, and quality assurance" --color "1D76DB"
gh label create "agent: forge 🔨" --description "Consensus-critical code (EVM, mining, crypto)" --color "D93F0B"
gh label create "agent: herald 🧭" --description "Network protocol and peer communication" --color "5319E7"
gh label create "agent: Q 🎯" --description "Process guardian and quality discipline" --color "C5DEF5"
```

## Creating Labels via API

You can also create labels using the GitHub REST API:

```bash
# Set your GitHub token
TOKEN="your_github_token"
OWNER="chippr-robotics"
REPO="fukuii"

# Create wraith label
curl -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/$OWNER/$REPO/labels \
  -d '{
    "name": "agent: wraith 👻",
    "description": "Compilation errors and Scala 3 migration",
    "color": "B60205"
  }'

# Create mithril label
curl -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/$OWNER/$REPO/labels \
  -d '{
    "name": "agent: mithril ✨",
    "description": "Code modernization and Scala 3 features",
    "color": "FFD700"
  }'

# Create ICE label
curl -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/$OWNER/$REPO/labels \
  -d '{
    "name": "agent: ICE 🧊",
    "description": "Large-scale migrations and strategic planning",
    "color": "0E8A16"
  }'

# Create eye label
curl -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/$OWNER/$REPO/labels \
  -d '{
    "name": "agent: eye 👁️",
    "description": "Testing, validation, and quality assurance",
    "color": "1D76DB"
  }'

# Create forge label
curl -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/$OWNER/$REPO/labels \
  -d '{
    "name": "agent: forge 🔨",
    "description": "Consensus-critical code (EVM, mining, crypto)",
    "color": "D93F0B"
  }'

# Create herald label
curl -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/$OWNER/$REPO/labels \
  -d '{
    "name": "agent: herald 🧭",
    "description": "Network protocol and peer communication",
    "color": "5319E7"
  }'

# Create Q label
curl -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/$OWNER/$REPO/labels \
  -d '{
    "name": "agent: Q 🎯",
    "description": "Process guardian and quality discipline",
    "color": "C5DEF5"
  }'
```

## Verifying Labels

After creating the labels, verify they appear correctly:

1. Go to your repository's **Labels** page
2. Check that all 7 agent labels are present with their emojis
3. Verify the descriptions are correct
4. Test by manually adding a label to an issue or PR

## Color Scheme Rationale

The colors are chosen to indicate priority and risk level:
- **Red** (wraith): Immediate attention needed for compilation errors
- **Gold** (mithril): Valuable improvements to code quality
- **Green** (ICE): Strategic planning and long-term work
- **Blue** (eye): Quality assurance and validation
- **Orange** (forge): Critical, consensus-affecting changes
- **Purple** (herald): Network protocol and peer communication
- **Light Blue** (Q): Methodical process and quality oversight

## Next Steps

Once the labels are created:
1. The `agent: forge 🔨` label will be automatically applied by the labeler workflow
2. Other agent labels should be manually applied as needed
3. See [AGENT_LABELS.md](AGENT_LABELS.md) for guidance on when to use each label
