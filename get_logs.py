import json, urllib.request

import os
TOKEN = os.environ.get('GITHUB_TOKEN', '')
H = {'Authorization': 'Bearer ' + TOKEN, 'Accept': 'application/vnd.github+json', 'User-Agent': 'zyvo-watcher'}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def get(url):
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=H)))

def get_raw(url):
    # follow redirects manually, re-adding auth
    opener = urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(url, headers=H)
    try:
        resp = opener.open(req)
        return resp.read()
    except urllib.error.HTTPError as e:
        if e.status in (301, 302, 303, 307):
            newurl = e.headers['Location']
            req2 = urllib.request.Request(newurl, headers=H)
            return urllib.request.urlopen(req2).read()
        raise

run = get('https://api.github.com/repos/zyvo9/zyvo-app/actions/runs?per_page=3')['workflow_runs'][0]
jobs = get(run['jobs_url'])
j = jobs['jobs'][0]
raw = get_raw(j['url'] + '/logs').decode('utf-8', 'replace')
lines = raw.splitlines()
errs = [l for l in lines if '##[error]' in l]
print('error lines:')
for l in errs[:15]:
    print('  ', l[:200])
# also the gradle FAILURE block
for i, l in enumerate(lines):
    if l.strip() == 'FAILURE: Build failed with an exception.':
        print('\nfailure block:')
        for x in lines[i:i+12]:
            print('  ', x[:200])
        break
