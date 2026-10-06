"""Discord bot that gives Erlina the family hub on her iPhone.

Planned commands: /today, /dinner, /chores (with tick buttons), /memo.
New memos addressed to Erlina are delivered as DMs.
"""
import os

import discord
from discord import app_commands
from dotenv import load_dotenv

load_dotenv()

intents = discord.Intents.default()
client = discord.Client(intents=intents)
tree = app_commands.CommandTree(client)


@tree.command(description="What's on today")
async def today(interaction: discord.Interaction):
    await interaction.response.send_message("Nothing wired up yet.", ephemeral=True)


@client.event
async def on_ready():
    await tree.sync()
    print(f"Logged in as {client.user}")


if __name__ == "__main__":
    client.run(os.environ["DISCORD_TOKEN"])
