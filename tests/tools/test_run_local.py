import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from backend.server import ui_revision
from scripts import run_local


class LauncherRevisionTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.root=Path(self.directory.name)
        (self.root/'web').mkdir()
        (self.root/'web'/'index.html').write_text('<p>current synthetic UI</p>')

    def tearDown(self): self.directory.cleanup()

    def launch_with(self,health):
        output=io.StringIO()
        with patch.object(run_local,'ROOT',self.root), \
             patch('sys.argv',['run_local.py','--demo','--port','12345']), \
             patch.object(run_local.urllib.request,'urlopen',return_value=io.BytesIO(json.dumps(health).encode())), \
             patch.object(run_local.webbrowser,'open') as browser, \
             patch('backend.server.make_server') as server, \
             patch.object(run_local.getpass,'getpass') as prompt, contextlib.redirect_stdout(output):
            result=run_local.main()
        server.assert_not_called();prompt.assert_not_called()
        return result,output.getvalue(),browser.call_args_list

    def test_old_server_without_revision_is_never_opened_or_replaced(self):
        result,message,browser=self.launch_with({'service':'arrival-pet-prototype'})
        self.assertNotEqual(result,0);self.assertEqual(browser,[])
        self.assertIn('--port 8790',message)

    def test_different_ui_revision_is_never_reused(self):
        result,_,browser=self.launch_with({'service':'arrival-pet-prototype','uiRevision':'0'*64})
        self.assertNotEqual(result,0);self.assertEqual(browser,[])

    def test_ui_edit_does_not_allow_reuse_of_a_server_started_before_the_edit(self):
        previous=ui_revision(self.root/'web')
        (self.root/'web'/'index.html').write_text('<p>new synthetic UI</p>')
        result,_,browser=self.launch_with({'service':'arrival-pet-prototype','uiRevision':previous})
        self.assertNotEqual(result,0);self.assertEqual(browser,[])

    def test_matching_revision_reuses_the_exact_current_ui(self):
        revision=ui_revision(self.root/'web')
        result,_,browser=self.launch_with({'service':'arrival-pet-prototype','uiRevision':revision})
        self.assertEqual(result,0);self.assertEqual(len(browser),1)
        self.assertEqual(browser[0].args,('http://127.0.0.1:12345/',))


if __name__=='__main__': unittest.main()
