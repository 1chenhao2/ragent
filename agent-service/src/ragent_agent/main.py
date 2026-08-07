from ragent_agent.api.app import create_app
from ragent_agent.config import AgentServiceSettings

app = create_app(AgentServiceSettings())
