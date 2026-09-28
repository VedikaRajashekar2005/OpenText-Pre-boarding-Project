import os
import sys
from pathlib import Path


def main():
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

    os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'backend.settings')
    try:
        from django.core.management import execute_from_command_line
    except ImportError as exc:
        raise ImportError(
            "Couldn't import Django."
        ) from exc

    argv = sys.argv
    if len(argv) == 1:
        print('No command given — starting the dev server (runserver).')
        print('For other commands, run: python manage.py <command>\n')
        argv = [argv[0], 'runserver']

    execute_from_command_line(argv)

if __name__ == '__main__':
    main()
